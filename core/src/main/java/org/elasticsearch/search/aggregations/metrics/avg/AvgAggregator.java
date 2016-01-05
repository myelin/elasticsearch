/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.search.aggregations.metrics.avg;

import org.apache.lucene.index.LeafReaderContext;
import org.elasticsearch.common.lease.Releasables;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.common.util.DoubleArray;
import org.elasticsearch.common.util.LongArray;
import org.elasticsearch.index.fielddata.SortedNumericDoubleValues;
import org.elasticsearch.search.aggregations.*;
import org.elasticsearch.search.aggregations.metrics.NumericMetricsAggregator;
import org.elasticsearch.search.aggregations.pipeline.PipelineAggregator;
import org.elasticsearch.search.aggregations.support.AggregationContext;
import org.elasticsearch.search.aggregations.support.ValuesSource;
import org.elasticsearch.search.aggregations.support.ValuesSourceAggregatorFactory;
import org.elasticsearch.search.aggregations.support.ValuesSourceConfig;
import org.elasticsearch.search.aggregations.support.format.ValueFormatter;
import org.elasticsearch.search.aggregations.support.values.ScriptDoubleValues;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 *
 */
public class AvgAggregator extends NumericMetricsAggregator.SingleValue {

    final ValuesSource.Numeric valuesSource;

    LongArray counts;
    DoubleArray sums;
    ValueFormatter formatter;

    public AvgAggregator(String name, ValuesSource.Numeric valuesSource, ValueFormatter formatter,
                         AggregationContext context,
                         Aggregator parent, List<PipelineAggregator> pipelineAggregators, Map<String, Object> metaData) throws IOException {
        super(name, context, parent, pipelineAggregators, metaData);
        this.valuesSource = valuesSource;
        this.formatter = formatter;
        if (valuesSource != null) {
            final BigArrays bigArrays = context.bigArrays();
            counts = bigArrays.newLongArray(1, true);
            sums = bigArrays.newDoubleArray(1, true);
        }
    }

    @Override
    public boolean needsScores() {
        return valuesSource != null && valuesSource.needsScores();
    }

    @Override
    public LeafBucketCollector getLeafCollector(LeafReaderContext ctx,
                                                final LeafBucketCollector sub) throws IOException {
        if (valuesSource == null) {
            return LeafBucketCollector.NO_OP_COLLECTOR;
        }
        final BigArrays bigArrays = context.bigArrays();
        final SortedNumericDoubleValues values = valuesSource.doubleValues(ctx);
        return new LeafBucketCollectorBase(sub, values) {
            @Override
            public void collect(int doc, long bucket) throws IOException {
                counts = bigArrays.grow(counts, bucket + 1);
                sums = bigArrays.grow(sums, bucket + 1);

                values.setDocument(doc);
                final int valueCount = values.count();
                double sum = 0;
                long count = 0;
                if (values instanceof ScriptDoubleValues && ((ScriptDoubleValues)values).hasExtendedScriptResult()) {
                    // Avg aggregation with a script returning a weight or a list of weights
                    ScriptDoubleValues scriptDoubleValues = (ScriptDoubleValues)values;
                    Object weightObj = scriptDoubleValues.getExtendedScriptResult().get("weight");
                    Object weightsObj = scriptDoubleValues.getExtendedScriptResult().get("weights");
                    if(weightsObj instanceof List) {
                        List weights = (List) weightsObj;
                        for (int i = 0; i < valueCount; i++) {
                            // By default, a missing weight is considered equals to 1.
                            long weight = 1;
                            if(i < weights.size()) {
                                Object weightObjAtIndexI = weights.get(i);
                                if(weightObjAtIndexI instanceof Number) {
                                    weight = ((Number)weightObjAtIndexI).longValue();
                                } else {
                                    throw new AggregationExecutionException("Unsupported weight value [" + weightObjAtIndexI + "], should be a number");
                                }
                            }
                            count += weight;
                            sum += values.valueAt(i) * weight;
                        }
                    } else {
                        // by default weight is equals to 0
                        long weight = 1;
                        if(weightObj instanceof Number) {
                            weight = ((Number)weightObj).longValue();
                        }
                        for (int i = 0; i < valueCount; i++) {
                            count += weight;
                            sum += values.valueAt(i) * weight;
                        }
                    }
                } else {
                    count += valueCount;
                    for (int i = 0; i < valueCount; i++) {
                        sum += values.valueAt(i);
                    }
                }
                counts.increment(bucket, count);
                sums.increment(bucket, sum);
            }
        };
    }

    @Override
    public double metric(long owningBucketOrd) {
        if (valuesSource == null || owningBucketOrd >= sums.size()) {
            return Double.NaN;
        }
        return sums.get(owningBucketOrd) / counts.get(owningBucketOrd);
    }

    @Override
    public InternalAggregation buildAggregation(long bucket) {
        if (valuesSource == null || bucket >= sums.size()) {
            return buildEmptyAggregation();
        }
        return new InternalAvg(name, sums.get(bucket), counts.get(bucket), formatter, pipelineAggregators(), metaData());
    }

    @Override
    public InternalAggregation buildEmptyAggregation() {
        return new InternalAvg(name, 0.0, 0l, formatter, pipelineAggregators(), metaData());
    }

    public static class Factory extends ValuesSourceAggregatorFactory.LeafOnly<ValuesSource.Numeric> {

        public Factory(String name, String type, ValuesSourceConfig<ValuesSource.Numeric> valuesSourceConfig) {
            super(name, type, valuesSourceConfig);
        }

        @Override
        protected Aggregator createUnmapped(AggregationContext aggregationContext, Aggregator parent,
                                            List<PipelineAggregator> pipelineAggregators,
                                            Map<String, Object> metaData) throws IOException {
            return new AvgAggregator(name, null, config.formatter(), aggregationContext, parent, pipelineAggregators, metaData);
        }

        @Override
        protected Aggregator doCreateInternal(ValuesSource.Numeric valuesSource, AggregationContext aggregationContext, Aggregator parent,
                                              boolean collectsFromSingleBucket, List<PipelineAggregator> pipelineAggregators, Map<String, Object> metaData)
                throws IOException {
            return new AvgAggregator(name, valuesSource, config.formatter(), aggregationContext, parent, pipelineAggregators, metaData);
        }
    }

    @Override
    public void doClose() {
        Releasables.close(counts, sums);
    }

}
