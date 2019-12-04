package com.twilio.raas.sql;

import com.google.common.annotations.VisibleForTesting;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.EnumerableDefaults;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;
import java.util.Map;

import org.apache.calcite.linq4j.Enumerator;
import org.apache.calcite.linq4j.Grouping;
import org.apache.calcite.linq4j.function.Function0;
import org.apache.calcite.linq4j.function.Function1;
import org.apache.calcite.linq4j.function.Function2;

import java.util.Optional;
import java.util.stream.Collectors;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.calcite.linq4j.AbstractEnumerable;
import org.apache.calcite.linq4j.AbstractEnumerable2;

import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kudu.client.AsyncKuduScanner;
import org.apache.kudu.Schema;

/**
 * An {@link Enumerable} that *can* returns Kudu records in Ascending order on
 * their primary key. It does so by wrapping a {@link List} of
 * {@link CalciteKuduEnumerable}, querying each one for their next row and
 * comparing those rows. This requires each {@code CalciteKuduEnumerable} scan
 * only one {@link org.apache.kudu.client.Partition} within the Kudu Table.
 * This guarantees the first rows coming out of the
 * {@link org.apache.kudu.client.AsyncKuduScanner} will return rows sorted by
 * primary key.
 *
 * Enumerable will return in unsorted order unless
 * {@link SortableEnumerable#setSorted} is called.
 */
public final class SortableEnumerable extends AbstractEnumerable<Object[]> {
    private static final Logger logger = LoggerFactory.getLogger(SortableEnumerable.class);

    private final List<AsyncKuduScanner> scanners;

    private final AtomicBoolean scansShouldStop;
    private final Schema projectedSchema;
    private final Schema tableSchema;

    public final boolean sort;
  public final boolean groupByLimited;
    public final long limit;
    public final long offset;
    public final List<Integer> descendingSortedFieldIndices;

    public SortableEnumerable(
        List<AsyncKuduScanner> scanners,
        final AtomicBoolean scansShouldStop,
        final Schema projectedSchema,
        final Schema tableSchema,
        final long limit,
        final long offset,
        final boolean sort,
        final List<Integer> descendingSortedFieldIndices,
        final boolean groupByLimited) {
        this.scanners = scanners;
        this.scansShouldStop = scansShouldStop;
        this.projectedSchema = projectedSchema;
        this.tableSchema = tableSchema;
        this.limit = limit;
        this.offset = offset;
        // if we have an offset always sort by the primary key to ensure the rows are returned
        // in a predictible order
        this.sort = offset>0 || sort;
        this.descendingSortedFieldIndices = descendingSortedFieldIndices;
        if (groupByLimited && !this.sort) {
          throw new IllegalArgumentException(
              "Cannot apply limit on group by without sorting the results first");
        }
        this.groupByLimited = groupByLimited;
    }

    @VisibleForTesting
    List<AsyncKuduScanner> getScanners() {
        return scanners;
    }

    private boolean checkLimitReached(int totalMoves) {
        if (limit > 0 && !groupByLimited) {
            long moveOffset = offset > 0 ? offset : 0;
            if (totalMoves - moveOffset > limit) {
                return true;
            }
        }
        return false;
    }

    public Enumerator<Object[]> unsortedEnumerator(final int numScanners,
        final BlockingQueue<CalciteScannerMessage<CalciteRow>> messages) {
        return new Enumerator<Object[]>() {
            private int finishedScanners = 0;
            private Object[] next = null;
            private boolean finished = false;
            private int totalMoves = 0;
            private boolean movedToOffset = false;

            private void moveToOffset() {
                movedToOffset = true;
                if (offset > 0) {
                    while(totalMoves < offset && moveNext());
                }
            }

            @Override
            public boolean moveNext() {
                if (finished) {
                    return false;
                }
                if (!movedToOffset) {
                    moveToOffset();
                }
                CalciteScannerMessage<CalciteRow> fetched;
                do {
                    try {
                        fetched = messages.poll(350, TimeUnit.MILLISECONDS);
                    }
                    catch (InterruptedException interrupted) {
                        fetched = CalciteScannerMessage.createEndMessage();
                    }
                    if (fetched != null) {
                        if (fetched.type == CalciteScannerMessage.MessageType.ERROR) {
                            throw new RuntimeException("A scanner failed, failing whole query", fetched.failure.get());
                        }
                        if (fetched.type == CalciteScannerMessage.MessageType.CLOSE) {
                            if (++finishedScanners >= numScanners) {
                                finished = true;
                                return false;
                            }
                        }
                    }

                } while(fetched == null ||
                    fetched.type != CalciteScannerMessage.MessageType.ROW);
                next = fetched.row.get().rowData;
                totalMoves++;
                boolean limitReached = checkLimitReached(totalMoves);
                if (limitReached) {
                    scansShouldStop.set(true);
                }
                return !limitReached;
            }

            @Override
            public Object[] current() {
                return next;
            }

            @Override
            public void reset() {
                throw new RuntimeException("Cannot reset an UnsortedEnumerable");
            }

            @Override
            public void close() {
                scansShouldStop.set(true);
            }
        };
    }

    public Enumerator<Object[]> sortedEnumerator(final List<Enumerator<CalciteRow>> subEnumerables) {

        return new Enumerator<Object[]>() {
            private Object[] next = null;
            private List<Boolean> enumerablesWithRows = new ArrayList<>(subEnumerables.size());
            private int totalMoves = 0;

            private void moveToOffset() {
                if (offset > 0) {
                    while(totalMoves < offset && moveNext());
                }
            }

            @Override
            public boolean moveNext() {
                // @TODO: is possible for subEnumerables to be empty?
                if (subEnumerables.isEmpty()) {
                    return false;
                }

                if (enumerablesWithRows.isEmpty()) {
                    for (int idx = 0; idx < subEnumerables.size(); idx++) {
                        enumerablesWithRows.add(subEnumerables.get(idx).moveNext());
                    }
                    moveToOffset();
                    logger.debug("Setup scanners {}", enumerablesWithRows);
                }
                CalciteRow smallest = null;
                int chosenEnumerable = -1;
                for (int idx = 0; idx < subEnumerables.size(); idx++) {
                    if (enumerablesWithRows.get(idx)) {
                        final CalciteRow enumerablesNext = subEnumerables.get(idx).current();
                        if (smallest == null) {
                            logger.trace("smallest isn't set setting to {}", enumerablesNext.rowData);
                            smallest = enumerablesNext;
                            chosenEnumerable = idx;
                        }
                        else if (enumerablesNext.compareTo(smallest) < 0) {
                            logger.trace("{} is smaller then {}",
                                enumerablesNext.rowData, smallest.rowData);
                            smallest = enumerablesNext;
                            chosenEnumerable = idx;
                        }
                        else {
                            logger.trace("{} is larger then {}",
                                enumerablesNext.rowData, smallest.rowData);
                        }
                    }
                    else {
                        logger.trace("{} index doesn't have next", idx);
                    }
                }
                if (smallest == null) {
                  logger.error("Couldn't find a row. exit");
                    return false;
                }
                next = smallest.rowData;
                // Move the chosen one forward. The others have their smallest
                // already in the front of their queues.
                logger.trace("Chosen idx {} to move next", chosenEnumerable);
                enumerablesWithRows.set(chosenEnumerable,
                    subEnumerables.get(chosenEnumerable).moveNext());
                totalMoves++;
                boolean limitReached = checkLimitReached(totalMoves);

                if (limitReached) {
                    scansShouldStop.set(true);
                }
                return !limitReached;
            }

            @Override
            public Object[] current() {
                return next;
            }

            @Override
            public void reset() {
                subEnumerables
                    .stream()
                    .forEach(e -> e.reset());
            }

            @Override
            public void close() {
                subEnumerables.stream()
                    .forEach(enumerable -> enumerable.close());
            }
        };
    }

    @Override
    public Enumerator<Object[]> enumerator() {
        if (sort) {
            return sortedEnumerator(
                scanners
                .stream()
                .map(scanner -> {
                        final BlockingQueue<CalciteScannerMessage<CalciteRow>> rowResults = new LinkedBlockingQueue<>();

                        // Yuck!!! side effect within a mapper. This is because the
                        // callback and the CalciteKuduEnumerable need to both share
                        // queue.
                        scanner.nextRows()
                            .addBothDeferring(new ScannerCallback(scanner,
                                                                  rowResults,
                                                                  scansShouldStop,
                                                                  tableSchema,
                                                                  projectedSchema,
                                                                  descendingSortedFieldIndices));
                        // Limit is not required here. do not use it.
                        return new CalciteKuduEnumerable(
                            rowResults,
                            scansShouldStop
                        );
                    }
                )
                .map(enumerable -> enumerable.enumerator())
                .collect(Collectors.toList()));
        }
        final BlockingQueue<CalciteScannerMessage<CalciteRow>> messages = new LinkedBlockingQueue<>();
        scanners
            .stream()
            .forEach(scanner -> {
                    scanner.nextRows()
                        .addBothDeferring(
                            new ScannerCallback(scanner,
                                                messages,
                                                scansShouldStop,
                                                tableSchema,
                                                projectedSchema,
                                                descendingSortedFieldIndices));
                });
        final int numScanners = scanners.size();

        return unsortedEnumerator(numScanners, messages);
    }

  @Override
  public <TKey, TAccumulate, TResult> Enumerable<TResult> groupBy(
      Function1<Object[], TKey> keySelector,
      Function0<TAccumulate> accumulatorInitializer,
      Function2<TAccumulate, Object[], TAccumulate> accumulatorAdder,
      Function2<TKey, TAccumulate, TResult> resultSelector) {
    if (!groupByLimited) {
      return EnumerableDefaults.groupBy(getThis(), keySelector,
          accumulatorInitializer, accumulatorAdder, resultSelector);
    }

    int uniqueGroupCount = 0;
    final Map<TKey, TAccumulate> map = new HashMap<>();
    TKey lastKey = null;
    try (Enumerator<Object[]> os = getThis().enumerator()) {
      while (os.moveNext()) {
        Object[] o = os.current();
        TKey key = keySelector.apply(o);

        if (lastKey == null || !key.equals(lastKey)) {
          lastKey = key;
          uniqueGroupCount++;
          if (uniqueGroupCount > limit) {
            break;
          }
        }

        TAccumulate accumulator = map.get(key);
        if (accumulator == null) {
          accumulator = accumulatorInitializer.apply();
          accumulator = accumulatorAdder.apply(accumulator, o);
          map.put(key, accumulator);
        }
        else {
          TAccumulate originalAccumulate = accumulator;
          accumulator = accumulatorAdder.apply(accumulator, o);
          if (originalAccumulate != accumulator) {
            map.put(key, accumulator);
          }
        }
      }
    }
    return new LookupResultEnumerable<>(map, resultSelector);
  }

  /** Reads a populated map, applying a selector function.
   *
   * @param <TResult> result type
   * @param <TKey> key type
   * @param <TAccumulate> accumulator type */
  private static class LookupResultEnumerable<TResult, TKey, TAccumulate>
      extends AbstractEnumerable2<TResult> {
    private final Map<TKey, TAccumulate> map;
    private final Function2<TKey, TAccumulate, TResult> resultSelector;

    LookupResultEnumerable(Map<TKey, TAccumulate> map,
        Function2<TKey, TAccumulate, TResult> resultSelector) {
      this.map = map;
      this.resultSelector = resultSelector;
    }

    public Iterator<TResult> iterator() {
      final Iterator<Map.Entry<TKey, TAccumulate>> iterator =
          map.entrySet().iterator();
      return new Iterator<TResult>() {
        public boolean hasNext() {
          return iterator.hasNext();
        }

        public TResult next() {
          final Map.Entry<TKey, TAccumulate> entry = iterator.next();
          return resultSelector.apply(entry.getKey(), entry.getValue());
        }

        public void remove() {
          throw new UnsupportedOperationException();
        }
      };
    }
  }
}
