package org.apache.hadoop.mapreduce;

public interface PostResourceJobHook {
  void updateConfiguration(Job job);
}
