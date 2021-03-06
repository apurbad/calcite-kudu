/* Copyright 2020 Twilio, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.twilio.kudu.sql.rules;

import com.google.common.collect.Lists;
import com.twilio.kudu.sql.KuduQuery;
import com.twilio.kudu.sql.KuduRelNode;
import com.twilio.kudu.sql.rel.KuduSortRel;
import com.twilio.kudu.sql.rel.KuduToEnumerableRel;
import org.apache.calcite.adapter.enumerable.EnumerableLimit;
import org.apache.calcite.plan.Convention;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelOptRuleOperand;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.RelCollationTraitDef;
import org.apache.calcite.rel.RelCollations;
import org.apache.calcite.rel.RelFieldCollation;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.RelFactories;
import org.apache.calcite.rel.core.Sort;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.tools.RelBuilderFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In recent Calcite Release, I believe this rule can be replaced.
 */
public abstract class KuduSortedAggregationRule extends KuduSortRule {

  private static final RelOptRuleOperand SIMPLE_OPERAND = operand(Sort.class,
      some(operand(Aggregate.class, some(operand(KuduToEnumerableRel.class,
          some(operand(Project.class, some(operand(Filter.class, some(operand(KuduQuery.class, none())))))))))));

  private static final RelOptRuleOperand LIMIT_OPERAND = operand(EnumerableLimit.class,
      some(operand(Sort.class, some(operand(Aggregate.class, some(operand(KuduToEnumerableRel.class,
          some(operand(Project.class, some(operand(Filter.class, some(operand(KuduQuery.class, none())))))))))))));

  public static final RelOptRule SORTED_AGGREGATION_LIMIT_RULE = new KuduSortedAggregationWithLimitRule(
      RelFactories.LOGICAL_BUILDER);

  public static final RelOptRule SORTED_AGGREGATION_RULE = new KuduSortedAggregationWithoutLimitRule(
      RelFactories.LOGICAL_BUILDER);

  public KuduSortedAggregationRule(RelOptRuleOperand operand, RelBuilderFactory relBuilderFactory, String description) {
    super(operand, relBuilderFactory, description);
  }

  protected void perform(final RelOptRuleCall call, final Optional<EnumerableLimit> originalLimit,
      final Sort originalSort, final Aggregate originalAggregate, final KuduToEnumerableRel kuduToEnumerableRel,
      final Project project, final Filter filter, final KuduQuery query) {
    /**
     * This rule should check that the columns being grouped by are also present in
     * sort.
     *
     * For a table with PK(A,B) A, B, C, 1, 1, 1, 1, 2, 2, 1, 3, 1, A query that
     * does GROUP BY A, C ORDER BY A cannot use this rule.
     */
    for (Integer groupedOrdinal : originalAggregate.getGroupSet()) {
      if (groupedOrdinal < query.calciteKuduTable.getKuduTable().getSchema().getPrimaryKeyColumnCount()) {
        boolean found = false;
        for (RelFieldCollation fieldCollation : originalSort.getCollation().getFieldCollations()) {
          if (fieldCollation.getFieldIndex() == groupedOrdinal
              && project.getProjects().get(fieldCollation.getFieldIndex()).getKind() == SqlKind.INPUT_REF) {
            found = true;
            break;
          }
        }
        if (!found) {
          return;
        }
      } else {
        // group by field is not a member of the primary key. Order cannot be exploited
        // for group keys.
        return;
      }
    }
    // Take the sorted fields and remap them through the group ordinals and then
    // through the
    // project ordinals.
    // This maps directly to the Kudu field indexes. The Map is between originalSort
    // ordinal ->
    // Kudu Field index.
    final Map<Integer, Integer> remappingOrdinals = new HashMap<>();

    final List<Integer> groupSet = originalAggregate.getGroupSet().asList();
    for (RelFieldCollation fieldCollation : originalSort.getCollation().getFieldCollations()) {
      int groupOrdinal = fieldCollation.getFieldIndex();
      int projectOrdinal = groupSet.get(groupOrdinal);
      int kuduColumnIndex = ((RexInputRef) project.getProjects().get(projectOrdinal)).getIndex();
      remappingOrdinals.put(fieldCollation.getFieldIndex(), kuduColumnIndex);
    }

    RelCollation newCollation = RelCollationTraitDef.INSTANCE
        .canonize(RelCollations.permute(originalSort.getCollation(), remappingOrdinals));
    final RelTraitSet traitSet = originalSort.getTraitSet().plus(newCollation).plus(Convention.NONE);

    // Check the new trait set to see if we can apply the sort against this.
    if (!canApply(traitSet, query, query.calciteKuduTable.getKuduTable(), Optional.of(filter))) {
      return;
    }

    final KuduSortRel newSort = new KuduSortRel(project.getCluster(), traitSet.replace(KuduRelNode.CONVENTION),
        convert(project, project.getTraitSet().replace(RelCollations.EMPTY)), newCollation,
        originalLimit.isPresent() ? originalLimit.get().offset : originalSort.offset,
        originalLimit.isPresent() ? originalLimit.get().fetch : originalSort.fetch, true);

    // Copy in the new collation because! this new rel is now coming out Sorted.
    final RelNode newkuduToEnumerableRel = kuduToEnumerableRel
        .copy(kuduToEnumerableRel.getTraitSet().replace(newCollation), Lists.newArrayList(newSort));

    // Create a the aggregation relation and indicate it is sorted based on result.
    // Add in our sorted collation into the aggregation as the input:
    // kuduToEnumerable
    // is coming out sorted because the kudu sorted rel is enabling it.
    final RelNode newAggregation = originalAggregate.copy(
        originalAggregate.getTraitSet().replace(originalSort.getCollation()),
        Collections.singletonList(newkuduToEnumerableRel));

    call.transformTo(newAggregation);
  }

  /**
   * Rule to match an aggregation over a sort over a prefix of the primary key
   * columns with a limit
   */
  public static class KuduSortedAggregationWithLimitRule extends KuduSortedAggregationRule {

    public KuduSortedAggregationWithLimitRule(final RelBuilderFactory factory) {
      super(LIMIT_OPERAND, factory, "KuduSortedAggregationWithLimit");
    }

    @Override
    public void onMatch(final RelOptRuleCall call) {
      final EnumerableLimit originalLimit = (EnumerableLimit) call.getRelList().get(0);
      final Sort originalSort = (Sort) call.getRelList().get(1);
      final Aggregate originalAggregate = (Aggregate) call.getRelList().get(2);
      final KuduToEnumerableRel kuduToEnumerableRel = (KuduToEnumerableRel) call.getRelList().get(3);
      final Project project = (Project) call.getRelList().get(4);
      final Filter filter = (Filter) call.getRelList().get(5);
      final KuduQuery query = (KuduQuery) call.getRelList().get(6);

      perform(call, Optional.of(originalLimit), originalSort, originalAggregate, kuduToEnumerableRel, project, filter,
          query);
    }
  }

  /**
   * Rule to match an aggregation over a sort over a prefix of the primary key
   * columns without a limit
   */
  public static class KuduSortedAggregationWithoutLimitRule extends KuduSortedAggregationRule {

    public KuduSortedAggregationWithoutLimitRule(final RelBuilderFactory factory) {
      super(SIMPLE_OPERAND, factory, "KuduSortedAggregation");
    }

    @Override
    public void onMatch(final RelOptRuleCall call) {
      final Sort originalSort = (Sort) call.getRelList().get(0);
      final Aggregate originalAggregate = (Aggregate) call.getRelList().get(1);
      final KuduToEnumerableRel kuduToEnumerableRel = (KuduToEnumerableRel) call.getRelList().get(2);
      final Project project = (Project) call.getRelList().get(3);
      final Filter filter = (Filter) call.getRelList().get(4);
      final KuduQuery query = (KuduQuery) call.getRelList().get(5);

      perform(call, Optional.empty(), originalSort, originalAggregate, kuduToEnumerableRel, project, filter, query);
    }
  }

}
