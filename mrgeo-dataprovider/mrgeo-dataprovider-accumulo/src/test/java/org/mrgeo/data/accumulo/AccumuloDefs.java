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

package org.mrgeo.data.accumulo;

import java.io.File;

public class AccumuloDefs
{
  /*
   * values for testing connections and to use with connections
   */
  public final static String KEY = "mock";

  public final static String USER_KEY = KEY + ".user";
  public final static String USER = "root";
  
  public final static String PASSWORD_KEY = KEY + ".password";
  public final static String PASSWORD = "secret";
  
  public final static String PASSWORDBLANK = "";

  public final static String INSTANCE_KEY = KEY + ".instance";
  public final static String INSTANCE = "accumulo";
  
  public final static String ZOOKEEPERS_KEY = KEY + ".zookeepers";
  public final static String ZOOKEEPERS = "localhost:2181";
  
  public final static String TABLE_KEY = KEY + ".table";
  public final static String TABLE = "junk";
  
  public final static String CWD = System.getProperty("user.dir") + File.separator;
  public final static String INPUTDIR = "testFiles" + File.separator;
  public final static String INPUTMETADATADIR = "metadata" + File.separator;
  
  public final static String INPUTMETADATAFILE = "metadata";
  
} // end AccumuloDefs
