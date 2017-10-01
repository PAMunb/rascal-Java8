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
package org.apache.cassandra.locator;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.List;

public interface DynamicEndpointSnitchMBean {
    public Map<InetAddress, Double> getScores();
    public int getUpdateInterval();
    public int getResetInterval();
    public double getBadnessThreshold();
    public String getSubsnitchClassName();
    public List<Double> dumpTimings(String hostname) throws UnknownHostException;
    /**
     * Use this if you want to specify a severity; it can be negative
     * Example: Page cache is cold and you want data to be sent 
     *          though it is not preferred one.
     */
    public void setSeverity(double severity);
    public double getSeverity();
}
