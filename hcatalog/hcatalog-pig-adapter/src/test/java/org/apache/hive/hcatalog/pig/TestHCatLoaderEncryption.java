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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.hive.hcatalog.pig;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.apache.commons.io.FileUtils;

import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.cli.CliSessionState;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.ql.CommandNeedRetryException;
import org.apache.hadoop.hive.ql.Driver;
import org.apache.hadoop.hive.ql.io.IOConstants;
import org.apache.hadoop.hive.ql.io.StorageFormats;
import org.apache.hadoop.hive.ql.processors.CommandProcessor;
import org.apache.hadoop.hive.ql.processors.CommandProcessorFactory;
import org.apache.hadoop.hive.ql.processors.CommandProcessorResponse;
import org.apache.hadoop.hive.ql.processors.HiveCommand;
import org.apache.hadoop.hive.ql.session.SessionState;
import org.apache.hadoop.hive.shims.HadoopShims;
import org.apache.hadoop.hive.shims.ShimLoader;

import org.apache.hive.hcatalog.HcatTestUtils;
import org.apache.hive.hcatalog.common.HCatUtil;
import org.apache.hive.hcatalog.data.Pair;

import org.apache.pig.ExecType;
import org.apache.pig.PigServer;
import org.apache.pig.data.Tuple;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

@RunWith(Parameterized.class)
public class TestHCatLoaderEncryption {
  private static final Logger LOG = LoggerFactory.getLogger(TestHCatLoader.class);
  private static final String TEST_DATA_DIR = HCatUtil.makePathASafeFileName(System.getProperty
      ("java.io.tmpdir") + File.separator + TestHCatLoader.class.getCanonicalName() + "-" +
      System.currentTimeMillis());
  private static final String TEST_WAREHOUSE_DIR = TEST_DATA_DIR + "/warehouse";
  private static final String BASIC_FILE_NAME = TEST_DATA_DIR + "/basic.input.data";
  private static final String BASIC_TABLE = "junit_unparted_basic";
  private static final String ENCRYPTED_TABLE = "encrypted_table";
  private static final String SECURITY_KEY_PROVIDER_URI_NAME = "dfs.encryption.key.provider.uri";

  private HadoopShims.MiniDFSShim dfs = null;
  private HadoopShims.HdfsEncryptionShim hes = null;
  private final String[] testOnlyCommands = new String[]{"crypto"};
  private final String[] encryptionUnsupportedHadoopVersion = new String[]{ShimLoader
      .HADOOP20SVERSIONNAME};
  private boolean isEncryptionTestEnabled = true;
  private Driver driver;
  private Map<Integer, Pair<Integer, String>> basicInputData;

  private static final Map<String, Set<String>> DISABLED_STORAGE_FORMATS =
      new HashMap<String, Set<String>>() {{
        put(IOConstants.PARQUETFILE, new HashSet<String>() {{
          add("testReadDataBasic");
          add("testReadPartitionedBasic");
          add("testProjectionsBasic");
          add("testReadDataFromEncryptedHiveTable");
        }});
      }};

  private String storageFormat;

  @Parameterized.Parameters
  public static Collection<Object[]> generateParameters() {
    return StorageFormats.names();
  }

  public TestHCatLoaderEncryption(String storageFormat) {
    this.storageFormat = storageFormat;
  }

  private void dropTable(String tablename) throws IOException, CommandNeedRetryException {
    dropTable(tablename, driver);
  }

  static void dropTable(String tablename, Driver driver) throws IOException, CommandNeedRetryException {
    driver.run("drop table if exists " + tablename);
  }

  private void createTable(String tablename, String schema, String partitionedBy) throws IOException, CommandNeedRetryException {
    createTable(tablename, schema, partitionedBy, driver, storageFormat);
  }

  static void createTable(String tablename, String schema, String partitionedBy, Driver driver, String storageFormat)
      throws IOException, CommandNeedRetryException {
    String createTable;
    createTable = "create table " + tablename + "(" + schema + ") ";
    if ((partitionedBy != null) && (!partitionedBy.trim().isEmpty())) {
      createTable = createTable + "partitioned by (" + partitionedBy + ") ";
    }
    createTable = createTable + "stored as " +storageFormat;
    executeStatementOnDriver(createTable, driver);
  }

  private void createTable(String tablename, String schema) throws IOException, CommandNeedRetryException {
    createTable(tablename, schema, null);
  }

  /**
   * Execute Hive CLI statement
   * @param cmd arbitrary statement to execute
   */
  static void executeStatementOnDriver(String cmd, Driver driver) throws IOException, CommandNeedRetryException {
    LOG.debug("Executing: " + cmd);
    CommandProcessorResponse cpr = driver.run(cmd);
    if(cpr.getResponseCode() != 0) {
      throw new IOException("Failed to execute \"" + cmd + "\". Driver returned " + cpr.getResponseCode() + " Error: " + cpr.getErrorMessage());
    }
  }

  @Before
  public void setup() throws Exception {
    File f = new File(TEST_WAREHOUSE_DIR);
    if (f.exists()) {
      FileUtil.fullyDelete(f);
    }
    if (!(new File(TEST_WAREHOUSE_DIR).mkdirs())) {
      throw new RuntimeException("Could not create " + TEST_WAREHOUSE_DIR);
    }

    HiveConf hiveConf = new HiveConf(this.getClass());
    hiveConf.set(HiveConf.ConfVars.PREEXECHOOKS.varname, "");
    hiveConf.set(HiveConf.ConfVars.POSTEXECHOOKS.varname, "");
    hiveConf.set(HiveConf.ConfVars.HIVE_SUPPORT_CONCURRENCY.varname, "false");
    hiveConf.set(HiveConf.ConfVars.METASTOREWAREHOUSE.varname, TEST_WAREHOUSE_DIR);
    driver = new Driver(hiveConf);

    checkShimLoaderVersion();
    initEncryptionShim(hiveConf);
    String encryptedTablePath =  TEST_WAREHOUSE_DIR + "/encryptedTable";
    SessionState.start(new CliSessionState(hiveConf));

    SessionState.get().out = System.out;

    createTable(BASIC_TABLE, "a int, b string");
    createTableInSpecifiedPath(ENCRYPTED_TABLE, "a int, b string", encryptedTablePath, driver);

    associateEncryptionZoneWithPath(encryptedTablePath);

    int LOOP_SIZE = 3;
    String[] input = new String[LOOP_SIZE * LOOP_SIZE];
    basicInputData = new HashMap<Integer, Pair<Integer, String>>();
    int k = 0;
    for (int i = 1; i <= LOOP_SIZE; i++) {
      String si = i + "";
      for (int j = 1; j <= LOOP_SIZE; j++) {
        String sj = "S" + j + "S";
        input[k] = si + "\t" + sj;
        basicInputData.put(k, new Pair<Integer, String>(i, sj));
        k++;
      }
    }
    HcatTestUtils.createTestDataFile(BASIC_FILE_NAME, input);
    PigServer server = new PigServer(ExecType.LOCAL);
    server.setBatchOn();
    int i = 0;
    server.registerQuery("A = load '" + BASIC_FILE_NAME + "' as (a:int, b:chararray);", ++i);
    server.registerQuery("store A into '" + ENCRYPTED_TABLE + "' using org.apache.hive.hcatalog.pig.HCatStorer();", ++i);
    server.executeBatch();
  }

  void checkShimLoaderVersion() {
    for (String v : encryptionUnsupportedHadoopVersion) {
      if (ShimLoader.getMajorVersion().equals(v)) {
        isEncryptionTestEnabled = false;
        return;
      }
    }
  }

  void initEncryptionShim(HiveConf conf) throws IOException {
    if (!isEncryptionTestEnabled) {
      return;
    }
    FileSystem fs;
    HadoopShims shims = ShimLoader.getHadoopShims();
    conf.set(SECURITY_KEY_PROVIDER_URI_NAME, getKeyProviderURI());

    int numberOfDataNodes = 4;
    dfs = shims.getMiniDfs(conf, numberOfDataNodes, true, null);
    fs = dfs.getFileSystem();

    // set up a java key provider for encrypted hdfs cluster
    hes = shims.createHdfsEncryptionShim(fs, conf);
  }

  public static String ensurePathEndsInSlash(String path) {
    if (path == null) {
      throw new NullPointerException("Path cannot be null");
    }
    if (path.endsWith(File.separator)) {
      return path;
    } else {
      return path + File.separator;
    }
  }

  private void associateEncryptionZoneWithPath(String path) throws SQLException, CommandNeedRetryException {
    if (!isEncryptionTestEnabled) {
      return;
    }
    LOG.info(this.storageFormat + ": associateEncryptionZoneWithPath");
    assumeTrue(!TestUtil.shouldSkip(storageFormat, DISABLED_STORAGE_FORMATS));
    enableTestOnlyCmd(SessionState.get().getConf());
    CommandProcessor crypto = getTestCommand("crypto");
    if (crypto == null) return;
    checkExecutionResponse(crypto.run("CREATE_KEY --keyName key_128 --bitLength 128"));
    checkExecutionResponse(crypto.run("CREATE_ZONE --keyName key_128 --path " + path));
  }

  private void checkExecutionResponse(CommandProcessorResponse response) {
    int rc = response.getResponseCode();
    if (rc != 0) {
      SessionState.get().out.println(response);
    }
    assertEquals("Crypto command failed with the exit code" + rc, 0, rc);
  }

  private void removeEncryptionZone() throws SQLException, CommandNeedRetryException {
    if (!isEncryptionTestEnabled) {
      return;
    }
    LOG.info(this.storageFormat + ": removeEncryptionZone");
    enableTestOnlyCmd(SessionState.get().getConf());
    CommandProcessor crypto = getTestCommand("crypto");
    if (crypto == null) {
      return;
    }
    checkExecutionResponse(crypto.run("DELETE_KEY --keyName key_128"));
  }

  private CommandProcessor getTestCommand(final String commandName) throws SQLException {
    HiveCommand testCommand = HiveCommand.find(new String[]{commandName}, HiveCommand.ONLY_FOR_TESTING);

    if (testCommand == null) {
      return null;
    }

    return CommandProcessorFactory
        .getForHiveCommandInternal(new String[]{commandName}, SessionState.get().getConf(),
            testCommand.isOnlyForTesting());
  }

  private void enableTestOnlyCmd(HiveConf conf){
    StringBuilder securityCMDs = new StringBuilder(conf.getVar(HiveConf.ConfVars.HIVE_SECURITY_COMMAND_WHITELIST));
    for(String c : testOnlyCommands){
      securityCMDs.append(",");
      securityCMDs.append(c);
    }
    conf.set(HiveConf.ConfVars.HIVE_SECURITY_COMMAND_WHITELIST.toString(), securityCMDs.toString());
  }

  private String getKeyProviderURI() {
    // Use the target directory if it is not specified
    String HIVE_ROOT = ensurePathEndsInSlash(System.getProperty("hive.root"));
    String keyDir = HIVE_ROOT + "ql/target/";

    // put the jks file in the current test path only for test purpose
    return "jceks://file" + new Path(keyDir, "test.jks").toUri();
  }

  @Test
  public void testReadDataFromEncryptedHiveTable() throws IOException {
    assumeTrue(isEncryptionTestEnabled);
    assumeTrue(!TestUtil.shouldSkip(storageFormat, DISABLED_STORAGE_FORMATS));
    PigServer server = new PigServer(ExecType.LOCAL);

    server.registerQuery("X = load '" + ENCRYPTED_TABLE + "' using org.apache.hive.hcatalog.pig.HCatLoader();");
    Iterator<Tuple> XIter = server.openIterator("X");
    int numTuplesRead = 0;
    while (XIter.hasNext()) {
      Tuple t = XIter.next();
      assertEquals(2, t.size());
      assertNotNull(t.get(0));
      assertNotNull(t.get(1));
      assertTrue(t.get(0).getClass() == Integer.class);
      assertTrue(t.get(1).getClass() == String.class);
      assertEquals(t.get(0), basicInputData.get(numTuplesRead).first);
      assertEquals(t.get(1), basicInputData.get(numTuplesRead).second);
      numTuplesRead++;
    }
    assertEquals("failed with storage format: " + this.storageFormat, basicInputData.size(), numTuplesRead);
  }

  @After
  public void tearDown() throws Exception {
    try {
      if (driver != null) {
        dropTable(BASIC_TABLE);
        dropTable(ENCRYPTED_TABLE);
        removeEncryptionZone();
      }
    } finally {
      FileUtils.deleteDirectory(new File(TEST_DATA_DIR));
    }
  }

  static void createTableInSpecifiedPath(String tableName, String schema, String path, Driver driver) throws IOException, CommandNeedRetryException {
    String createTableStr;
    createTableStr = "create table " + tableName + "(" + schema + ") location \'" + path + "\'";
    executeStatementOnDriver(createTableStr, driver);
  }
}