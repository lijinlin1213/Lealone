/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.lealone.cluster.dht;

import java.net.InetAddress;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.lealone.cluster.db.ClusterMetaData;
import org.lealone.cluster.db.Keyspace;
import org.lealone.cluster.gms.EndpointState;
import org.lealone.cluster.gms.Gossiper;
import org.lealone.cluster.gms.IFailureDetector;
import org.lealone.cluster.locator.AbstractReplicationStrategy;
import org.lealone.cluster.locator.IEndpointSnitch;
import org.lealone.cluster.locator.TokenMetaData;
import org.lealone.cluster.streaming.StreamPlan;
import org.lealone.cluster.streaming.StreamResultFuture;
import org.lealone.cluster.utils.Utils;
import org.lealone.db.schema.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;

/**
 * Assists in streaming ranges to a node.
 */
public class RangeStreamer {
    private static final Logger logger = LoggerFactory.getLogger(RangeStreamer.class);

    /* bootstrap tokens. can be null if replacing the node. */
    private final Collection<Token> tokens;
    /* current token ring */
    private final TokenMetaData metadata;
    /* address of this node */
    private final InetAddress address; // 是当前节点的地址，并不是将要被请求的节点的地址
    /* streaming description */
    private final String description;
    // map的key是keyspaceName
    private final Multimap<String, Map.Entry<InetAddress, Collection<Range<Token>>>> toFetch = HashMultimap.create();
    private final Set<ISourceFilter> sourceFilters = new HashSet<>();
    private final StreamPlan streamPlan;
    private final boolean useStrictConsistency;
    private final IEndpointSnitch snitch;

    /**
     * A filter applied to sources to stream from when constructing a fetch map.
     */
    public static interface ISourceFilter {
        public boolean shouldInclude(InetAddress endpoint);
    }

    /**
     * Source filter which excludes any endpoints that are not alive according to a
     * failure detector.
     */
    public static class FailureDetectorSourceFilter implements ISourceFilter {
        private final IFailureDetector fd;

        public FailureDetectorSourceFilter(IFailureDetector fd) {
            this.fd = fd;
        }

        @Override
        public boolean shouldInclude(InetAddress endpoint) {
            return fd.isAlive(endpoint);
        }
    }

    /**
     * Source filter which excludes any endpoints that are not in a specific data center.
     */
    public static class SingleDatacenterFilter implements ISourceFilter {
        private final String sourceDc;
        private final IEndpointSnitch snitch;

        public SingleDatacenterFilter(IEndpointSnitch snitch, String sourceDc) {
            this.sourceDc = sourceDc;
            this.snitch = snitch;
        }

        @Override
        public boolean shouldInclude(InetAddress endpoint) {
            return snitch.getDatacenter(endpoint).equals(sourceDc);
        }
    }

    public RangeStreamer(TokenMetaData metadata, Collection<Token> tokens, InetAddress address, String description,
            boolean useStrictConsistency, IEndpointSnitch snitch) {
        this.metadata = metadata;
        this.tokens = tokens;
        this.address = address;
        this.description = description;
        this.streamPlan = new StreamPlan(description);
        this.useStrictConsistency = useStrictConsistency;
        this.snitch = snitch;
    }

    public void addSourceFilter(ISourceFilter filter) {
        sourceFilters.add(filter);
    }

    /**
     * Add ranges to be streamed for given keyspace.
     *
     * @param keyspaceName keyspace name
     * @param ranges ranges to be streamed
     */
    public void addRanges(Schema schema, Collection<Range<Token>> ranges) {
        Multimap<Range<Token>, InetAddress> rangesForKeyspace = useStrictSourcesForRanges(schema) ? getAllRangesWithStrictSourcesFor(
                schema, ranges) : getAllRangesWithSourcesFor(schema, ranges);

        String schemaName = schema.getFullName();
        if (logger.isDebugEnabled()) {
            for (Map.Entry<Range<Token>, InetAddress> entry : rangesForKeyspace.entries())
                logger.debug(String.format("%s: range %s exists on %s", description, entry.getKey(), entry.getValue()));
        }

        for (Map.Entry<InetAddress, Collection<Range<Token>>> entry : getRangeFetchMap(rangesForKeyspace,
                sourceFilters, schemaName).asMap().entrySet()) {
            if (logger.isDebugEnabled()) {
                for (Range<Token> r : entry.getValue())
                    logger.debug(String.format("%s: range %s from source %s for keyspace %s", description, r,
                            entry.getKey(), schemaName));
            }
            toFetch.put(schemaName, entry);
        }
    }

    /**
     * @param schema schema to check
     * @return true when the node is bootstrapping, useStrictConsistency is true and # of nodes in the cluster is more than # of replica
     */
    private boolean useStrictSourcesForRanges(Schema schema) {
        AbstractReplicationStrategy strat = Keyspace.getReplicationStrategy(schema);
        return useStrictConsistency && tokens != null
                && metadata.getAllEndpoints().size() != strat.getReplicationFactor();
    }

    /**
     * Get a map of all ranges and their respective sources that are candidates for streaming the given ranges
     * to us. For each range, the list of sources is sorted by proximity relative to the given destAddress.
     *
     * @throws java.lang.IllegalStateException when there is no source to get data streamed
     */
    // 返回的结果中，一个desiredRange可能有多个InetAddress
    private Multimap<Range<Token>, InetAddress> getAllRangesWithSourcesFor(Schema schema,
            Collection<Range<Token>> desiredRanges) {
        AbstractReplicationStrategy strat = Keyspace.getReplicationStrategy(schema);
        Multimap<Range<Token>, InetAddress> rangeAddresses = strat.getRangeAddresses(metadata.cloneOnlyTokenMap());

        Multimap<Range<Token>, InetAddress> rangeSources = ArrayListMultimap.create();
        for (Range<Token> desiredRange : desiredRanges) {
            for (Range<Token> range : rangeAddresses.keySet()) {
                if (range.contains(desiredRange)) {
                    List<InetAddress> preferred = snitch.getSortedListByProximity(address, rangeAddresses.get(range));
                    rangeSources.putAll(desiredRange, preferred);
                    break;
                }
            }

            if (!rangeSources.keySet().contains(desiredRange))
                throw new IllegalStateException("No sources found for " + desiredRange);
        }

        return rangeSources;
    }

    /**
     * Get a map of all ranges and the source that will be cleaned up once this bootstrapped node is added for the given ranges.
     * For each range, the list should only contain a single source. This allows us to consistently migrate data without violating
     * consistency.
     *
     * @throws java.lang.IllegalStateException when there is no source to get data streamed, or more than 1 source found.
     */
    // 返回的结果中，一个desiredRange只有一个InetAddress
    private Multimap<Range<Token>, InetAddress> getAllRangesWithStrictSourcesFor(Schema schema,
            Collection<Range<Token>> desiredRanges) {
        assert tokens != null;
        AbstractReplicationStrategy strat = Keyspace.getReplicationStrategy(schema);

        // Active ranges
        TokenMetaData metadataClone = metadata.cloneOnlyTokenMap();
        Multimap<Range<Token>, InetAddress> rangeAddresses = strat.getRangeAddresses(metadataClone);

        // Pending ranges
        metadataClone.updateNormalTokens(tokens, address);
        Multimap<Range<Token>, InetAddress> pendingRangeAddresses = strat.getRangeAddresses(metadataClone);

        // Collects the source that will have its range moved to the new node
        Multimap<Range<Token>, InetAddress> rangeSources = ArrayListMultimap.create();

        for (Range<Token> desiredRange : desiredRanges) {
            for (Map.Entry<Range<Token>, Collection<InetAddress>> preEntry : rangeAddresses.asMap().entrySet()) {
                if (preEntry.getKey().contains(desiredRange)) {
                    Set<InetAddress> oldEndpoints = Sets.newHashSet(preEntry.getValue());
                    Set<InetAddress> newEndpoints = Sets.newHashSet(pendingRangeAddresses.get(desiredRange));

                    // Due to CASSANDRA-5953 we can have a higher RF then we have endpoints.
                    // So we need to be careful to only be strict when endpoints == RF
                    if (oldEndpoints.size() == strat.getReplicationFactor()) {
                        oldEndpoints.removeAll(newEndpoints);
                        assert oldEndpoints.size() == 1 : "Expected 1 endpoint but found " + oldEndpoints.size();
                    }

                    rangeSources.put(desiredRange, oldEndpoints.iterator().next());
                }
            }

            // Validate
            Collection<InetAddress> addressList = rangeSources.get(desiredRange);
            if (addressList == null || addressList.isEmpty())
                throw new IllegalStateException("No sources found for " + desiredRange);

            if (addressList.size() > 1)
                throw new IllegalStateException("Multiple endpoints found for " + desiredRange);

            InetAddress sourceIp = addressList.iterator().next();
            EndpointState sourceState = Gossiper.instance.getEndpointStateForEndpoint(sourceIp);
            if (Gossiper.instance.isEnabled() && (sourceState == null || !sourceState.isAlive()))
                throw new RuntimeException(
                        "A node required to move the data consistently is down ("
                                + sourceIp
                                + "). "
                                + "If you wish to move the data from a potentially inconsistent replica, restart the node with -Dcassandra.consistent.rangemovement=false");
        }

        return rangeSources;
    }

    /**
     * @param rangesWithSources The ranges we want to fetch (key) and their potential sources (value)
     * @param sourceFilters A (possibly empty) collection of source filters to apply. In addition to any filters given
     *                      here, we always exclude ourselves.
     * @param keyspace keyspace name
     * @return Map of source endpoint to collection of ranges
     */
    private static Multimap<InetAddress, Range<Token>> getRangeFetchMap(
            Multimap<Range<Token>, InetAddress> rangesWithSources, Collection<ISourceFilter> sourceFilters,
            String keyspace) {
        Multimap<InetAddress, Range<Token>> rangeFetchMapMap = HashMultimap.create();
        for (Range<Token> range : rangesWithSources.keySet()) {
            boolean foundSource = false;

            outer: for (InetAddress address : rangesWithSources.get(range)) {
                if (address.equals(Utils.getBroadcastAddress())) {
                    // If localhost is a source, we have found one, but we don't add it to the map to avoid streaming
                    // locally
                    foundSource = true;
                    continue;
                }

                for (ISourceFilter filter : sourceFilters) {
                    if (!filter.shouldInclude(address))
                        continue outer;
                }

                rangeFetchMapMap.put(address, range);
                foundSource = true;
                break; // ensure we only stream from one other node for each range
            }

            if (!foundSource)
                throw new IllegalStateException("unable to find sufficient sources for streaming range " + range
                        + " in keyspace " + keyspace);
        }

        return rangeFetchMapMap;
    }

    public static Multimap<InetAddress, Range<Token>> getWorkMap(
            Multimap<Range<Token>, InetAddress> rangesWithSourceTarget, String keyspace, IFailureDetector fd) {
        return getRangeFetchMap(rangesWithSourceTarget,
                Collections.<ISourceFilter> singleton(new FailureDetectorSourceFilter(fd)), keyspace);
    }

    // For testing purposes
    @VisibleForTesting
    Multimap<String, Map.Entry<InetAddress, Collection<Range<Token>>>> toFetch() {
        return toFetch;
    }

    public StreamResultFuture fetchAsync() {
        for (Map.Entry<String, Map.Entry<InetAddress, Collection<Range<Token>>>> entry : toFetch.entries()) {
            String keyspace = entry.getKey();
            InetAddress source = entry.getValue().getKey();
            InetAddress preferred = ClusterMetaData.getPreferredIP(source);
            Collection<Range<Token>> ranges = entry.getValue().getValue();

            // filter out already streamed ranges
            // Set<Range<Token>> availableRanges = stateStore.getAvailableRanges(keyspace,
            // StorageService.instance.getTokenMetaData().partitioner);
            // if (ranges.removeAll(availableRanges)) {
            // logger.info("Some ranges of {} are already available. Skipping streaming those ranges.",
            // availableRanges);
            // }

            if (logger.isDebugEnabled())
                logger.debug("{}ing from {} ranges {}", description, source, StringUtils.join(ranges, ", "));
            /* Send messages to respective folks to stream data over to me */
            streamPlan.requestRanges(source, preferred, keyspace, ranges);
        }

        return streamPlan.execute();
    }
}
