/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.gobblin.yarn;

import com.google.common.base.Predicate;
import com.google.common.eventbus.EventBus;
import com.google.common.io.Closer;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigValueFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import org.apache.gobblin.testing.AssertWithBackoff;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.yarn.api.records.ApplicationAttemptId;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.ApplicationReport;
import org.apache.hadoop.yarn.api.records.ApplicationSubmissionContext;
import org.apache.hadoop.yarn.api.records.Container;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.api.records.ContainerLaunchContext;
import org.apache.hadoop.yarn.api.records.Priority;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.api.records.YarnApplicationState;
import org.apache.hadoop.yarn.client.api.YarnClient;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.apache.hadoop.yarn.server.MiniYARNCluster;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.attempt.RMAppAttempt;
import org.apache.hadoop.yarn.server.utils.BuilderUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;


/**
 * Tests for {@link YarnService}.
 */
@Test(groups = {"gobblin.yarn", "disabledOnTravis"}, singleThreaded=true)
public class YarnServiceTest {
  final Logger LOG = LoggerFactory.getLogger(YarnServiceTest.class);

  private YarnClient yarnClient;
  private MiniYARNCluster yarnCluster;
  private TestYarnService yarnService;
  private Config config;
  private YarnConfiguration clusterConf;
  private ApplicationId applicationId;
  private ApplicationAttemptId applicationAttemptId;
  private final EventBus eventBus = new EventBus("YarnServiceTest");

  private final Closer closer = Closer.create();

  private static void setEnv(String key, String value) {
    try {
      Map<String, String> env = System.getenv();
      Class<?> cl = env.getClass();
      Field field = cl.getDeclaredField("m");
      field.setAccessible(true);
      Map<String, String> writableEnv = (Map<String, String>) field.get(env);
      writableEnv.put(key, value);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to set environment variable", e);
    }
  }

  @BeforeClass
  public void setUp() throws Exception {
    // Set java home in environment since it isn't set on some systems
    String javaHome = System.getProperty("java.home");
    setEnv("JAVA_HOME", javaHome);

    this.clusterConf = new YarnConfiguration();
    this.clusterConf.set(YarnConfiguration.RM_NM_HEARTBEAT_INTERVAL_MS, "100");
    this.clusterConf.set(YarnConfiguration.RESOURCEMANAGER_CONNECT_MAX_WAIT_MS, "10000");
    this.clusterConf.set(YarnConfiguration.YARN_CLIENT_APPLICATION_CLIENT_PROTOCOL_POLL_TIMEOUT_MS, "60000");

    this.yarnCluster =
        this.closer.register(new MiniYARNCluster("YarnServiceTestCluster", 4, 1,
            1));
    this.yarnCluster.init(this.clusterConf);
    this.yarnCluster.start();

    // YARN client should not be started before the Resource Manager is up
    AssertWithBackoff.create().logger(LOG).timeoutMs(10000)
        .assertTrue(new Predicate<Void>() {
          @Override public boolean apply(Void input) {
            return !clusterConf.get(YarnConfiguration.RM_ADDRESS).contains(":0");
          }
        }, "Waiting for RM");

    this.yarnClient = this.closer.register(YarnClient.createYarnClient());
    this.yarnClient.init(this.clusterConf);
    this.yarnClient.start();

    URL url = YarnServiceTest.class.getClassLoader()
        .getResource(YarnServiceTest.class.getSimpleName() + ".conf");
    Assert.assertNotNull(url, "Could not find resource " + url);

    this.config = ConfigFactory.parseURL(url).resolve();

    // Start a dummy application manager so that the YarnService can use the AM-RM token.
    startApp();

    // create and start the test yarn service
    this.yarnService = new TestYarnService(this.config, "testApp", "appId",
        this.clusterConf,
        FileSystem.getLocal(new Configuration()), this.eventBus);

   this.yarnService.startUp();
  }

  private void startApp() throws Exception {
    // submit a dummy app
    ApplicationSubmissionContext appSubmissionContext =
        yarnClient.createApplication().getApplicationSubmissionContext();
    this.applicationId = appSubmissionContext.getApplicationId();

    ContainerLaunchContext containerLaunchContext =
        BuilderUtils.newContainerLaunchContext(Collections.emptyMap(), Collections.emptyMap(),
            Arrays.asList("sleep", "100"), Collections.emptyMap(), null, Collections.emptyMap());

    // Setup the application submission context
    appSubmissionContext.setApplicationName("TestApp");
    appSubmissionContext.setResource(Resource.newInstance(128, 1));
    appSubmissionContext.setPriority(Priority.newInstance(0));
    appSubmissionContext.setAMContainerSpec(containerLaunchContext);

    this.yarnClient.submitApplication(appSubmissionContext);

    // wait for application to be accepted
    int i;
    RMAppAttempt attempt = null;
    for (i = 0; i < 120; i++) {
      ApplicationReport appReport = yarnClient.getApplicationReport(applicationId);

      if (appReport.getYarnApplicationState() == YarnApplicationState.ACCEPTED) {
        this.applicationAttemptId = appReport.getCurrentApplicationAttemptId();
        attempt = yarnCluster.getResourceManager().getRMContext().getRMApps()
            .get(appReport.getCurrentApplicationAttemptId().getApplicationId()).getCurrentAppAttempt();
        break;
      }
      Thread.sleep(1000);
    }

    Assert.assertTrue(i < 120, "timed out waiting for ACCEPTED state");

    // Set the AM-RM token in the UGI for access during testing
    UserGroupInformation.setLoginUser(UserGroupInformation.createRemoteUser(UserGroupInformation.getCurrentUser()
        .getUserName()));
    UserGroupInformation.getCurrentUser().addToken(attempt.getAMRMToken());
  }

  @AfterClass
  public void tearDown() throws IOException, TimeoutException, YarnException {
    try {
      this.yarnClient.killApplication(this.applicationAttemptId.getApplicationId());
      this.yarnService.shutDown();
    } finally {
      this.closer.close();
    }
  }

  /**
   * Test that the dynamic config is added to the config specified when the {@link GobblinApplicationMaster}
   * is instantiated.
   */
  @Test(groups = {"gobblin.yarn", "disabledOnTravis"})
  public void testScaleUp() {
    this.yarnService.requestTargetNumberOfContainers(10, Collections.EMPTY_SET);

    Assert.assertEquals(this.yarnService.getNumRequestedContainers(), 10);
    Assert.assertTrue(this.yarnService.waitForContainerCount(10, 60000));
  }

  @Test(groups = {"gobblin.yarn", "disabledOnTravis"}, dependsOnMethods = "testScaleUp")
  public void testScaleDownWithInUseInstances() {
    Set<String> inUseInstances = new HashSet<>();

    for (int i = 1; i <= 8; i++) {
      inUseInstances.add("GobblinYarnTaskRunner_" + i);
    }

    this.yarnService.requestTargetNumberOfContainers(6, inUseInstances);

    Assert.assertEquals(this.yarnService.getNumRequestedContainers(), 6);

    // will only be able to shrink to 8
    Assert.assertTrue(this.yarnService.waitForContainerCount(8, 60000));

    // will not be able to shrink to 6 due to 8 in-use instances
    Assert.assertFalse(this.yarnService.waitForContainerCount(6, 10000));

  }

  @Test(groups = {"gobblin.yarn", "disabledOnTravis"}, dependsOnMethods = "testScaleDownWithInUseInstances")
  public void testScaleDown() throws Exception {
    this.yarnService.requestTargetNumberOfContainers(4, Collections.EMPTY_SET);

    Assert.assertEquals(this.yarnService.getNumRequestedContainers(), 4);
    Assert.assertTrue(this.yarnService.waitForContainerCount(4, 60000));
  }

  // Keep this test last since it interferes with the container counts in the prior tests.
  @Test(groups = {"gobblin.yarn", "disabledOnTravis"}, dependsOnMethods = "testScaleDown")
  public void testReleasedContainerCache() throws Exception {
    Config modifiedConfig = this.config
        .withValue(GobblinYarnConfigurationKeys.RELEASED_CONTAINERS_CACHE_EXPIRY_SECS, ConfigValueFactory.fromAnyRef("2"));
    TestYarnService yarnService =
        new TestYarnService(modifiedConfig, "testApp1", "appId1",
            this.clusterConf, FileSystem.getLocal(new Configuration()), this.eventBus);

    ContainerId containerId1 = ContainerId.newInstance(ApplicationAttemptId.newInstance(ApplicationId.newInstance(1, 0),
        0), 0);

    yarnService.getReleasedContainerCache().put(containerId1, "");

    Assert.assertTrue(yarnService.getReleasedContainerCache().getIfPresent(containerId1) != null);

    // give some time for element to expire
    Thread.sleep(4000);
    Assert.assertTrue(yarnService.getReleasedContainerCache().getIfPresent(containerId1) == null);
  }

  @Test(groups = {"gobblin.yarn", "disabledOnTravis"}, dependsOnMethods = "testReleasedContainerCache")
  public void testBuildContainerCommand() throws Exception {
    Config modifiedConfig = this.config
        .withValue(GobblinYarnConfigurationKeys.CONTAINER_JVM_MEMORY_OVERHEAD_MBS_KEY, ConfigValueFactory.fromAnyRef("10"))
        .withValue(GobblinYarnConfigurationKeys.CONTAINER_JVM_MEMORY_XMX_RATIO_KEY, ConfigValueFactory.fromAnyRef("0.8"));

    TestYarnService yarnService =
        new TestYarnService(modifiedConfig, "testApp2", "appId2",
            this.clusterConf, FileSystem.getLocal(new Configuration()), this.eventBus);

    ContainerId containerId = ContainerId.newInstance(ApplicationAttemptId.newInstance(ApplicationId.newInstance(1, 0),
        0), 0);
    Resource resource = Resource.newInstance(2048, 1);
    Container container = Container.newInstance(containerId, null, null, resource, null, null);

    String command = yarnService.buildContainerCommand(container, "helixInstance1");

    // 1628 is from 2048 * 0.8 - 10
    Assert.assertTrue(command.contains("-Xmx1628"));
  }


  private static class TestYarnService extends YarnService {
    public TestYarnService(Config config, String applicationName, String applicationId, YarnConfiguration yarnConfiguration,
        FileSystem fs, EventBus eventBus) throws Exception {
      super(config, applicationName, applicationId, yarnConfiguration, fs, eventBus);
    }

    protected ContainerLaunchContext newContainerLaunchContext(Container container, String helixInstanceName)
        throws IOException {
      return BuilderUtils.newContainerLaunchContext(Collections.emptyMap(), Collections.emptyMap(),
              Arrays.asList("sleep", "60000"), Collections.emptyMap(), null, Collections.emptyMap());
    }

    /**
     * Wait to reach the expected count.
     *
     * @param expectedCount the expected count
     * @param waitMillis amount of time in milliseconds to wait
     * @return true if the count was reached within the allowed wait time
     */
    public boolean waitForContainerCount(int expectedCount, int waitMillis) {
      final int waitInterval = 1000;
      int waitedMillis = 0;
      boolean success = false;

      while (waitedMillis < waitMillis) {
        try {
          Thread.sleep(waitInterval);
          waitedMillis += waitInterval;
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }

        if (expectedCount == getContainerMap().size()) {
          success = true;
          break;
        }
      }

      return success;
    }
  }
}
