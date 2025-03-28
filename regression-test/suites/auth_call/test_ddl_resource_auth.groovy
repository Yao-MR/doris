// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

import org.junit.Assert;

suite("test_ddl_resource_auth","p0,auth_call") {
    String user = 'test_ddl_resource_auth_user'
    String pwd = 'C123_567p'
    String dbName = 'test_ddl_resource_auth_db'
    String resourceName = 'test_ddl_resource_auth_rs'

    //cloud-mode
    if (isCloudMode()) {
        return
    }

    try_sql("DROP USER ${user}")
    try_sql """drop database if exists ${dbName}"""
    try_sql("""DROP RESOURCE '${resourceName}'""")
    sql """CREATE USER '${user}' IDENTIFIED BY '${pwd}'"""
    sql """grant select_priv on regression_test to ${user}"""
    sql """create database ${dbName}"""

    // ddl create,show,drop
    connect(user, "${pwd}", context.config.jdbcUrl) {
        test {
            sql """CREATE RESOURCE IF NOT EXISTS "${resourceName}"
                    PROPERTIES(
                        "type" = "s3",
                        "AWS_ENDPOINT" = "bj.s3.comaaaa",
                        "AWS_REGION" = "bj",
                        "AWS_ROOT_PATH" = "path/to/rootaaaa",
                        "AWS_ACCESS_KEY" = "bbba",
                        "AWS_SECRET_KEY" = "aaaa",
                        "AWS_MAX_CONNECTIONS" = "50",
                        "AWS_REQUEST_TIMEOUT_MS" = "3000",
                        "AWS_CONNECTION_TIMEOUT_MS" = "1000",
                        "AWS_BUCKET" = "test-bucket",
                        "s3_validity_check" = "false"
                    );"""
            exception "denied"
        }
        test {
            sql """ALTER RESOURCE '${resourceName}' PROPERTIES ("s3.connection.maximum" = "100");"""
            exception "denied"
        }

        def res = sql """SHOW RESOURCES WHERE NAME = '${resourceName}'"""
        assertTrue(res.size() == 0)

        test {
            sql """DROP RESOURCE '${resourceName}'"""
            exception "denied"
        }
    }
    sql """grant admin_priv on *.*.* to ${user}"""
    connect(user, "${pwd}", context.config.jdbcUrl) {
        sql """CREATE RESOURCE IF NOT EXISTS "${resourceName}"
                PROPERTIES(
                    "type" = "s3",
                    "AWS_ENDPOINT" = "bj.s3.comaaaa",
                    "AWS_REGION" = "bj",
                    "AWS_ROOT_PATH" = "path/to/rootaaaa",
                    "AWS_ACCESS_KEY" = "bbba",
                    "AWS_SECRET_KEY" = "aaaa",
                    "AWS_MAX_CONNECTIONS" = "50",
                    "AWS_REQUEST_TIMEOUT_MS" = "3000",
                    "AWS_CONNECTION_TIMEOUT_MS" = "1000",
                    "AWS_BUCKET" = "test-bucket",
                    "s3_validity_check" = "false"
                );"""
        def res = sql """SHOW RESOURCES WHERE NAME = '${resourceName}'"""
        assertTrue(res.size() > 0)
        sql """ALTER RESOURCE '${resourceName}' PROPERTIES ("s3.connection.maximum" = "100");"""
        sql """DROP RESOURCE '${resourceName}'"""
        res = sql """SHOW RESOURCES WHERE NAME = '${resourceName}'"""
        assertTrue(res.size() == 0)
    }

    try_sql("""DROP RESOURCE '${resourceName}'""")
    sql """drop database if exists ${dbName}"""
    try_sql("DROP USER ${user}")
}
