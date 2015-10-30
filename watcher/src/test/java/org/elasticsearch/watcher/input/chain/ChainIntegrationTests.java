/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.watcher.input.chain;

import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.node.Node;
import org.elasticsearch.watcher.input.http.HttpInput;
import org.elasticsearch.watcher.support.http.HttpRequestTemplate;
import org.elasticsearch.watcher.support.http.auth.basic.BasicAuth;
import org.elasticsearch.watcher.test.AbstractWatcherIntegrationTestCase;
import org.junit.Test;

import java.net.InetSocketAddress;

import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertHitCount;
import static org.elasticsearch.watcher.actions.ActionBuilders.indexAction;
import static org.elasticsearch.watcher.client.WatchSourceBuilders.watchBuilder;
import static org.elasticsearch.watcher.input.InputBuilders.*;
import static org.elasticsearch.watcher.trigger.TriggerBuilders.schedule;
import static org.elasticsearch.watcher.trigger.schedule.Schedules.interval;
import static org.hamcrest.Matchers.containsString;

public class ChainIntegrationTests extends AbstractWatcherIntegrationTestCase {

    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        return Settings.builder()
                .put(super.nodeSettings(nodeOrdinal))
                .put(Node.HTTP_ENABLED, true)
                .build();
    }

    public void testChainedInputsAreWorking() throws Exception {
        String index = "the-most-awesome-index-ever";
        createIndex(index);
        client().prepareIndex(index, "type", "id").setSource("{}").setRefresh(true).get();

        InetSocketAddress address = internalCluster().httpAddresses()[0];
        HttpInput.Builder httpInputBuilder = httpInput(HttpRequestTemplate.builder(address.getHostString(), address.getPort())
                .path("{{ctx.payload.first.url}}")
                .body(jsonBuilder().startObject().field("size", 1).endObject())
                .auth(shieldEnabled() ? new BasicAuth("test", "changeme".toCharArray()) : null));

        ChainInput.Builder chainedInputBuilder = chainInput()
                .add("first", simpleInput("url", "/" + index  + "/_search"))
                .add("second", httpInputBuilder);

        watcherClient().preparePutWatch("_name")
                .setSource(watchBuilder()
                        .trigger(schedule(interval("5s")))
                        .input(chainedInputBuilder)
                        .addAction("indexAction", indexAction("my-index", "my-type")))
                .get();

        if (timeWarped()) {
            timeWarp().scheduler().trigger("_name");
            refresh();
        }

        refresh();
        SearchResponse searchResponse = client().prepareSearch("my-index").setTypes("my-type").get();
        assertHitCount(searchResponse, 1);
        assertThat(searchResponse.getHits().getAt(0).sourceAsString(), containsString(index));
        assertWatchWithMinimumPerformedActionsCount("_name", 1, false);
    }


}
