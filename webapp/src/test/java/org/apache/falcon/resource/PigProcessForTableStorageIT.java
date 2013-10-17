/**
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

package org.apache.falcon.resource;

import org.apache.falcon.entity.ClusterHelper;
import org.apache.falcon.entity.v0.cluster.Cluster;
import org.apache.falcon.entity.v0.cluster.Interfacetype;
import org.apache.falcon.util.HiveTestUtils;
import org.apache.falcon.util.OozieTestUtils;
import org.apache.falcon.util.StartupProperties;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hcatalog.api.HCatPartition;
import org.apache.oozie.client.OozieClient;
import org.apache.oozie.client.WorkflowJob;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import javax.ws.rs.core.MediaType;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Integration tests for Pig Processing Engine.
 *
 * This test is disabled as it heavily depends on oozie sharelibs for
 * pig and hcatalog being made available on HDFS. captured in FALCON-139.
 */
@Test (enabled = false)
public class PigProcessForTableStorageIT {

    private static final String DATABASE_NAME = "falcon_db";
    private static final String IN_TABLE_NAME = "input_table";
    private static final String OUT_TABLE_NAME = "output_table";
    private static final String PARTITION_VALUE = "2012-04-21-00"; // ${YEAR}-${MONTH}-${DAY}-${HOUR}
    private static final String CLUSTER_TEMPLATE = "/table/primary-cluster.xml";

    private final TestContext context = new TestContext();
    private Map<String, String> overlay;
    private String metastoreUrl;

    @BeforeClass
    public void prepare() throws Exception {
        TestContext.prepare();

        overlay = context.getUniqueOverlay();
        String filePath = context.overlayParametersOverTemplate(CLUSTER_TEMPLATE, overlay);
        context.setCluster(filePath);

        final Cluster cluster = context.getCluster().getCluster();
        String storageUrl = ClusterHelper.getStorageUrl(cluster);
        metastoreUrl = ClusterHelper.getInterface(cluster, Interfacetype.REGISTRY).getEndpoint();
        copyScriptsToHDFS(storageUrl);
        setupHiveMetastore(storageUrl);

        // set up kahadb to be sent as part of workflows
        StartupProperties.get().setProperty("libext.paths", "./target/libext");
        String libext = ClusterHelper.getLocation(cluster, "working") + "/libext";
        TestContext.copyOozieShareLibsToHDFS("./target/libext", storageUrl + libext);
    }

    private void copyScriptsToHDFS(String storageUrl) throws IOException {
        // copyPigScriptToHDFS
        TestContext.copyResourceToHDFS(
                "/apps/pig/table-id.pig", "table-id.pig", storageUrl + "/falcon/test/apps/pig");
        // copyTestDataToHDFS
        TestContext.copyResourceToHDFS(
                "/apps/pig/data.txt", "data.txt", storageUrl + "/falcon/test/input/" + PARTITION_VALUE);
    }

    private void setupHiveMetastore(String storageUrl) throws Exception {
        HiveTestUtils.dropTable(metastoreUrl, DATABASE_NAME, IN_TABLE_NAME);
        HiveTestUtils.dropTable(metastoreUrl, DATABASE_NAME, OUT_TABLE_NAME);
        HiveTestUtils.dropDatabase(metastoreUrl, DATABASE_NAME);

        HiveTestUtils.createDatabase(metastoreUrl, DATABASE_NAME);
        final List<String> partitionKeys = Arrays.asList("ds");
        HiveTestUtils.createTable(metastoreUrl, DATABASE_NAME, IN_TABLE_NAME, partitionKeys);
        final String sourcePath = storageUrl + "/falcon/test/input/" + PARTITION_VALUE;
        HiveTestUtils.loadData(metastoreUrl, DATABASE_NAME, IN_TABLE_NAME, sourcePath, PARTITION_VALUE);
        HiveTestUtils.createTable(metastoreUrl, DATABASE_NAME, OUT_TABLE_NAME, partitionKeys);
    }

    @AfterClass
    public void tearDown() throws Exception {
        HiveTestUtils.dropTable(metastoreUrl, DATABASE_NAME, OUT_TABLE_NAME);
        HiveTestUtils.dropTable(metastoreUrl, DATABASE_NAME, IN_TABLE_NAME);
        HiveTestUtils.dropDatabase(metastoreUrl, DATABASE_NAME);

        cleanupFS(context.getCluster().getCluster());
    }

    private void cleanupFS(Cluster cluster) throws IOException {
        FileSystem fs = FileSystem.get(ClusterHelper.getConfiguration(cluster));
        fs.delete(new Path("/falcon/test/input/" + PARTITION_VALUE), true);
    }

    @Test (enabled = false)
    public void testSubmitAndSchedulePigProcessForTableStorage() throws Exception {
        overlay.put("cluster", "primary-cluster");

        String filePath = context.overlayParametersOverTemplate(CLUSTER_TEMPLATE, overlay);
        Assert.assertEquals(0, TestContext.executeWithURL("entity -submit -type cluster -file " + filePath));

        filePath = context.overlayParametersOverTemplate("/table/table-feed-input.xml", overlay);
        Assert.assertEquals(0,
                TestContext.executeWithURL("entity -submitAndSchedule -type feed -file " + filePath));

        filePath = context.overlayParametersOverTemplate("/table/table-feed-output.xml", overlay);
        Assert.assertEquals(0,
                TestContext.executeWithURL("entity -submitAndSchedule -type feed -file " + filePath));

        final String pigProcessName = "pig-tables-" + context.getProcessName();
        overlay.put("processName", pigProcessName);

        filePath = context.overlayParametersOverTemplate("/table/pig-process-tables.xml", overlay);
        Assert.assertEquals(0,
                TestContext.executeWithURL("entity -submitAndSchedule -type process -file " + filePath));

        WorkflowJob jobInfo = OozieTestUtils.getWorkflowJob(context.getCluster().getCluster(),
                OozieClient.FILTER_NAME + "=FALCON_PROCESS_DEFAULT_" + pigProcessName);
        Assert.assertEquals(WorkflowJob.Status.SUCCEEDED, jobInfo.getStatus());

        HCatPartition partition = HiveTestUtils.getPartition(
                metastoreUrl, DATABASE_NAME, OUT_TABLE_NAME, "ds", PARTITION_VALUE);
        Assert.assertTrue(partition != null);

        InstancesResult response = context.service.path("api/instance/running/process/" + pigProcessName)
                .header("Remote-User", "guest")
                .accept(MediaType.APPLICATION_JSON)
                .get(InstancesResult.class);
        Assert.assertEquals(APIResult.Status.SUCCEEDED, response.getStatus());

        TestContext.executeWithURL("entity -delete -type process -name " + pigProcessName);
    }
}