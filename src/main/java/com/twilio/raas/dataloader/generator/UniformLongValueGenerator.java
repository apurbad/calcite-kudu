package com.twilio.raas.dataloader.generator;


import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Random;

public class UniformLongValueGenerator extends ColumnValueGenerator<Long> {

  private final Random rand = new Random();
  public long minValue;
  public long maxValue;

  private UniformLongValueGenerator() {
  }

  @JsonCreator
  public UniformLongValueGenerator(final long minVal, final long maxVal) {
    this.minValue = minVal;
    this.maxValue = maxVal;
  }

  @Override
  public Long getColumnValue() {
    return minValue + (long) (rand.nextDouble() * (maxValue - minValue));
  }
}
