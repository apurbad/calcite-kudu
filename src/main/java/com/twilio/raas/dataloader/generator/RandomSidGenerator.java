package com.twilio.raas.dataloader.generator;

public class RandomSidGenerator extends ColumnValueGenerator<String> {

  public String sidPrefix;

  private RandomSidGenerator() {
  }

  public RandomSidGenerator(final String sidPrefix) {
    this.sidPrefix = sidPrefix;
  }

  @Override
  public String getColumnValue() {
    return com.twilio.sids.SidUtil.generateGUID(sidPrefix);
  }

}