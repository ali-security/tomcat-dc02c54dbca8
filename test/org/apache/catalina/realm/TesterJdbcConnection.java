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

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal in-memory stand-in for a JDBC Connection that understands the
 * credential and role queries built by DataSourceRealm and JDBCRealm. Used
 * instead of an embedded database, which is not a build dependency here.
 */
public class TesterJdbcConnection {

    final Map<String,String> users = new HashMap<>();
    final Map<String,List<String>> roles = new HashMap<>();
    volatile boolean failRoleQueries = false;

    public void addUser(String name, String password, String... userRoles) {
        users.put(name, password);
        List<String> list = new ArrayList<>();
        for (String role : userRoles) {
            list.add(role);
        }
        roles.put(name, list);
    }

    public Connection getConnection() {
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { Connection.class }, (InvocationHandler) (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "prepareStatement":
                            return statement((String) args[0]);
                        case "isClosed":
                        case "getAutoCommit":
                            return Boolean.FALSE;
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });
    }

    private PreparedStatement statement(final String sql) {
        final String[] param = new String[1];
        return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { PreparedStatement.class }, (InvocationHandler) (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "setString":
                            param[0] = (String) args[1];
                            return null;
                        case "executeQuery":
                            return resultSet(sql, param[0]);
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });
    }

    private ResultSet resultSet(String sql, String user) throws SQLException {
        final List<String> rows = new ArrayList<>();
        if (sql == null || sql.contains("null")) {
            throw new SQLException("Invalid statement [" + sql + "]");
        }
        if (sql.contains("user_roles")) {
            if (failRoleQueries) {
                throw new SQLException("Simulated role lookup failure");
            }
            if (roles.containsKey(user)) {
                rows.addAll(roles.get(user));
            }
        } else if (users.containsKey(user)) {
            rows.add(users.get(user));
        }
        final int[] pos = new int[] { -1 };
        return (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { ResultSet.class }, (InvocationHandler) (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "next":
                            pos[0]++;
                            return Boolean.valueOf(pos[0] < rows.size());
                        case "getString":
                            return rows.get(pos[0]);
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return Boolean.FALSE;
        } else if (type == int.class) {
            return Integer.valueOf(0);
        } else if (type == long.class) {
            return Long.valueOf(0);
        }
        return null;
    }
}
