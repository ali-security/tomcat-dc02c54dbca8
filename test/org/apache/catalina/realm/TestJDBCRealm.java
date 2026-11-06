/*
* Licensed to the Apache Software Foundation (ASF) under one or more
* contributor license agreements.  See the NOTICE file distributed with
* this work for additional information regarding copyright ownership.
* The ASF licenses this file to You under the Apache License, Version 2.0
* (the "License"); you may not use this file except in compliance with
* the License.  You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package org.apache.catalina.realm;

import java.security.Principal;
import java.sql.Connection;

import org.junit.Assert;
import org.junit.Test;

import org.apache.catalina.LifecycleException;
import org.apache.catalina.startup.LoggingBaseTest;

public class TestJDBCRealm extends LoggingBaseTest {

    protected class TesterJDBCRealm extends JDBCRealm {
        protected final TesterJdbcConnection db;
        public TesterJDBCRealm(TesterJdbcConnection db) {
            this.db = db;
        }
        @Override
        protected Connection open() {
            // Replace the JDBC driver with the in-memory tester
            if (dbConnection == null) {
                dbConnection = db.getConnection();
            }
            return dbConnection;
        }
        @Override
        public void initInternal() throws LifecycleException {
            // Avoid heavy lifecycle and JMX, however container log is needed
            containerLog = log;
        }
    }

    private TesterJDBCRealm createRealm(TesterJdbcConnection tdb) {
        TesterJDBCRealm realm = new TesterJDBCRealm(tdb);
        realm.setUserTable("users");
        realm.setUserNameCol("user_name");
        realm.setUserCredCol("user_pass");
        realm.setUserRoleTable("user_roles");
        realm.setRoleNameCol("role_name");
        return realm;
    }

    @Test
    public void testRealm() throws Exception {
        TesterJdbcConnection tdb = new TesterJdbcConnection();
        tdb.addUser("tomcat", "password", "admin", "foo");
        tdb.addUser("random", "password");

        TesterJDBCRealm db = createRealm(tdb);
        db.start();

        Assert.assertNull(db.authenticate("tomcat", "bar"));
        Assert.assertNull(db.authenticate("blabla", "bar"));

        Principal p = db.authenticate("tomcat", "password");
        Assert.assertTrue(p instanceof GenericPrincipal);
        GenericPrincipal gp = (GenericPrincipal) p;
        Assert.assertTrue(gp.hasRole("foo"));
        Assert.assertTrue(gp.hasRole("admin"));

        p = db.getPrincipal("tomcat");
        Assert.assertTrue(p instanceof GenericPrincipal);
        gp = (GenericPrincipal) p;
        Assert.assertTrue(gp.hasRole("admin"));

        // Users that do not exist must not get a Principal
        Assert.assertNull(db.getPrincipal("blabla"));

        p = db.getPrincipal("random");
        Assert.assertTrue(p instanceof GenericPrincipal);
        gp = (GenericPrincipal) p;
        Assert.assertEquals(0, gp.getRoles().length);

        db.stop();
    }

    @Test
    public void testRoleLookupFailure() throws Exception {
        TesterJdbcConnection tdb = new TesterJdbcConnection();
        tdb.addUser("tomcat", "password", "admin");

        TesterJDBCRealm db = createRealm(tdb);
        db.start();

        tdb.failRoleQueries = true;

        Assert.assertNull(db.authenticate("tomcat", "password"));
        Assert.assertNull(db.getPrincipal("tomcat"));

        db.stop();
    }
}
