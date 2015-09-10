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
package org.apache.hive.hcatalog.templeton.tool;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.curator.framework.CuratorFramework;
import org.apache.hadoop.conf.Configuration;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs.Ids;

/*
 * The general idea here is to create
 * /created/1
 * /created/2
 * /created/3 ....
 * for each job submitted.  The node number is generated by ZK (PERSISTENT_SEQUENTIAL) and the 
 * payload is the JobId. Basically this keeps track of the order in which jobs were submitted,
 * and ZooKeeperCleanup uses this to purge old job info.
 * Since the /jobs/<id> node has a create/update timestamp 
 * (http://zookeeper.apache.org/doc/trunk/zookeeperProgrammers.html#sc_zkStatStructure) this whole
 * thing can be removed.
*/
public class JobStateTracker {
  // The path to the tracking root
  private String job_trackingroot = null;

  // The zookeeper connection to use
  private CuratorFramework zk;

  // The id of the tracking node -- must be a SEQUENTIAL node
  private String trackingnode;

  // The id of the job this tracking node represents
  private String jobid;

  // The logger
  private static final Log LOG = LogFactory.getLog(JobStateTracker.class);

  /**
   * Constructor for a new node -- takes the jobid of an existing job
   *
   */
  public JobStateTracker(String node, CuratorFramework zk, boolean nodeIsTracker,
               String job_trackingpath) {
    this.zk = zk;
    if (nodeIsTracker) {
      trackingnode = node;
    } else {
      jobid = node;
    }
    job_trackingroot = job_trackingpath;
  }

  /**
   * Create the parent znode for this job state.
   */
  public void create() throws IOException {
    try {
      zk.create().creatingParentsIfNeeded().withMode(CreateMode.PERSISTENT)
        .withACL(Ids.OPEN_ACL_UNSAFE).forPath(job_trackingroot);
    } catch (KeeperException.NodeExistsException e) {
      //root must exist already
    } catch (Exception e) {
      throw new IOException("Unable to create parent nodes");
    }
    try {
      trackingnode = zk.create().withMode(CreateMode.PERSISTENT_SEQUENTIAL)
        .withACL(Ids.OPEN_ACL_UNSAFE).forPath(makeTrackingZnode(), jobid.getBytes());
    } catch (Exception e) {
      throw new IOException("Unable to create " + makeTrackingZnode());
    }
  }
  public void delete() throws IOException {
    try {
      zk.delete().forPath(makeTrackingJobZnode(trackingnode));
    } catch (Exception e) {
      // Might have been deleted already
      LOG.info("Couldn't delete " + makeTrackingJobZnode(trackingnode));
    }
  }

  /**
   * Get the jobid for this tracking node
   * @throws IOException
   */
  public String getJobID() throws IOException {
    try {
      return new String(zk.getData().forPath(makeTrackingJobZnode(trackingnode)));
    } catch (Exception e) {
      // It was deleted during the transaction
      throw new IOException("Node already deleted " + trackingnode, e);
    }
  }

  /**
   * Make a ZK path to a new tracking node
   */
  public String makeTrackingZnode() {
    return job_trackingroot + "/";
  }

  /**
   * Make a ZK path to an existing tracking node
   */
  public String makeTrackingJobZnode(String nodename) {
    return job_trackingroot + "/" + nodename;
  }

  /*
   * Get the list of tracking jobs.  These can be used to determine which jobs have
   * expired.
   */
  public static List<String> getTrackingJobs(Configuration conf, CuratorFramework zk)
    throws IOException {
    ArrayList<String> jobs = new ArrayList<String>();
    try {
      for (String myid : zk.getChildren().forPath(
        conf.get(TempletonStorage.STORAGE_ROOT)
          + ZooKeeperStorage.TRACKINGDIR)) {
        jobs.add(myid);
      }
    } catch (Exception e) {
      throw new IOException("Can't get tracking children", e);
    }
    return jobs;
  }
}