package com.after00.repository;


import com.google.common.base.Splitter;

import org.elasticsearch.action.admin.indices.exists.indices.IndicesExistsRequest;
import org.elasticsearch.action.admin.indices.exists.indices.IndicesExistsResponse;
import org.elasticsearch.action.admin.indices.template.put.PutIndexTemplateRequest;
import org.elasticsearch.action.bulk.BackoffPolicy;
import org.elasticsearch.action.bulk.BulkProcessor;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.client.IndicesAdminClient;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.collect.MapBuilder;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.common.unit.ByteSizeUnit;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.xpack.client.PreBuiltXPackTransportClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;

@Repository
@Slf4j
public class ESRepository {
    public TransportClient client = null;
    public BulkProcessor bulkProcessor = null;

    @Value("${es.strClusterName}")
    private String strClusterName;

    @Value("${es.strTransportHostNames}")
    private String strTransportHostNames;

    private Splitter splitter = Splitter.on(",").trimResults();

    public void buildClient() throws Exception {
        log.info("init settings");
        Settings settings = Settings.builder()
                .put("cluster.name", strClusterName)
                .put("client.transport.sniff", false)//5.4.0版本设置为false，不然会告警，不知道为啥
                .put("xpack.security.user", "elastic:changeme")//for x-pack
                .build();
        log.info("init clinet");
        Iterable<String> itTransportHostName = splitter.split(strTransportHostNames);
        client = new PreBuiltXPackTransportClient(settings);//for x-pack
        log.info("init InetSocketTransportAddress");
        for (String strTransportHostName : itTransportHostName) {
            log.info("init host: {}", strTransportHostName);
            client.addTransportAddress(new TransportAddress(InetAddress.getByName(strTransportHostName), 9300));
        }
    }

    public void bulidBulkProcessor() throws Exception {
        bulkProcessor = BulkProcessor.builder(client, new BulkProcessor.Listener() {
            @Override
            public void beforeBulk(long executionId, BulkRequest request) {
            }

            @Override
            public void afterBulk(long executionId, BulkRequest request, BulkResponse response) {
            }

            @Override
            public void afterBulk(long executionId, BulkRequest request, Throwable failure) {
            }
        }).setBulkActions(5000)
                .setBulkSize(new ByteSizeValue(10, ByteSizeUnit.MB))
                .setFlushInterval(TimeValue.timeValueSeconds(10))
                .setConcurrentRequests(18)
                .setBackoffPolicy(
                        BackoffPolicy.exponentialBackoff(TimeValue.timeValueMillis(100), 3))
                .build();
    }

    public void closeClient() {
        if (client != null) {
            client.close();
        }
    }

    public void closeBulkProcessor() {
        if (bulkProcessor != null) {
            bulkProcessor.close();
        }
    }

    // 创建模版
    public void buildTemplate() throws Exception {
        String strTemplateNamePrefixs = "";
        Iterable<String> itTemplateNamePrefix = splitter.split(strTemplateNamePrefixs);
        IndicesAdminClient iac = client.admin().indices();
        for (String strTemplateNamePrefix : itTemplateNamePrefix) {
            PutIndexTemplateRequest pitr = new PutIndexTemplateRequest(strTemplateNamePrefix);
//                    .template(strTemplateNamePrefix + "*");
            //number_of_shards 机器数减一,number_of_replicas 备份1份就是两份
            //如果你用单机测试，这段需要注释掉
            //我有18个node，所以设置为18个，分配均匀的话，每个node上会有一个shard，外加一个备份，每个node上有两个shard
            //分片数*副本数=集群数量
            pitr.settings(new MapBuilder<String, Object>().put("number_of_shards", 18).put("number_of_replicas", 1)
                    .put("refresh_interval", "1s").map());
            Map<String, Object> defaultMapping = new HashMap<String, Object>();
            // 关闭_all
            defaultMapping.put("_all", new MapBuilder<String, Object>().put("enabled", false).map());
            defaultMapping.put("numeric_detection", false);
            defaultMapping.put("dynamic_templates",
                    new Object[]{
                            new MapBuilder<String, Object>().put("date_tpl",
                                    new MapBuilder<String, Object>().put("match", "dt*")
                                            .put("mapping",
                                                    new MapBuilder<String, Object>().put("type", "date")
                                                            .put("index", "not_analyzed").put("doc_values", true)
                                                            .map())
                                            .map())
                                    .map(),
                            new MapBuilder<String, Object>().put("geo_point_tpl",
                                    new MapBuilder<String, Object>().put("match", "geop*")
                                            .put("mapping",
                                                    new MapBuilder<String, Object>().put("type", "geo_point")
                                                            .put("index", "not_analyzed").put("doc_values", true)
                                                            .map())
                                            .map())
                                    .map(),
                            new MapBuilder<String, Object>().put("all_tpl",
                                    new MapBuilder<String, Object>().put("match", "*").put("mapping",
                                            new MapBuilder<String, Object>().put("type", "{dynamic_type}")
                                                    .put("index", "not_analyzed").put("doc_values", true).map())
                                            .map())
                                    .map()});
            pitr.mapping("_default_", defaultMapping);
            iac.putTemplate(pitr);
        }
    }

    //判断index是否存在
    public boolean exists(String strIndex) {
        IndicesExistsRequest request = new IndicesExistsRequest(strIndex);
        IndicesExistsResponse response = client.admin().indices().exists(request).actionGet();
        if (response.isExists()) {
            return true;
        }
        return false;
    }

    //删除index
    public void delete(String strIndex) {
        if (exists(strIndex)) {
            client.admin().indices().prepareDelete(strIndex).get();
        }
    }

    //创建index
    public void create(String strIndex, int nShards, int nReplicas) {
        client.admin().indices().prepareCreate(strIndex)
                .setSettings(Settings.builder()
                        .put("index.number_of_shards", nShards)
                        .put("index.number_of_replicas", nReplicas)
                        .put("index.refresh_interval", "10s")
                ).get();
    }

    //创建mapping
    public void putMapping(String strIndex, String strType, String strMapping) {
        try {
            client.admin().indices().preparePutMapping(strIndex)
                    .setType(strType)
                    .setSource(strMapping, XContentType.JSON)
                    .get();
        } catch (Exception e) {
            log.error("", e);
        }
    }

}
