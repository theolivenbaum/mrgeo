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

package org.mrgeo.data.accumulo.utils;

import junit.framework.Assert;
import org.apache.accumulo.core.client.Connector;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.mrgeo.junit.UnitTest;


public class AccumuloConnectorTest
{

  protected String u = "root";
  protected String pw = "secret";
  protected String inst = "accumulo";
  protected String zoo = "localhost:2181";

  
  @BeforeClass
  public static void init() throws Exception{} // end init

  @Before
  public void setup(){} // end setup
  

  @Ignore
  @Test
  @Category(UnitTest.class)
  public void testConnectorAll() throws Exception{
    Connector conn = AccumuloConnector.getConnector(inst, zoo, u, pw);
    
    Assert.assertNotNull(conn);

  } // end testConnectorAll
  
  
} // end AccumuloConnectorTest
