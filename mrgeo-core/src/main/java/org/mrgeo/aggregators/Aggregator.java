/*
 * Copyright 2009-2016 DigitalGlobe, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 *
 */

/**
 *
 */
package org.mrgeo.aggregators;

/**
 * An Aggregator takes an input array of 4 sample values
 * and returns an aggregated value according to the implemented
 * resampling technique.
 */
public interface Aggregator
{
  public double aggregate(double[] values, double nodata);

  public float aggregate(float[] values, float nodata);

  public int aggregate(int[] values, int nodata);
  
  public double aggregate(double[][]values, double weightx, double weighty, double nodata);
  public float aggregate(float[][]values, double weightx, double weighty, float nodata);
  public int aggregate(final int[][] values, final double weightx, final double weighty, final int nodata);
}
