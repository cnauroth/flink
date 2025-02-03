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

package org.apache.flink.fs.gs;

import org.apache.flink.configuration.Configuration;
import org.apache.flink.core.fs.FileSystem;
import org.apache.flink.core.fs.FileSystemBehaviorTestSuite;
import org.apache.flink.core.fs.FileSystemKind;
import org.apache.flink.core.fs.Path;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.ExtendWith;

/** An implementation of the {@link FileSystemBehaviorTestSuite} for the GCS file system. */
@ExtendWith(RequireGCSConfiguration.class)
class HadoopGSFileSystemBehaviorITCase extends FileSystemBehaviorTestSuite {

    @BeforeAll
    static void setup() {
        FileSystem.initialize(new Configuration());
    }

    @Override
    protected FileSystem getFileSystem() throws Exception {
        return getBasePath().getFileSystem();
    }

    @Override
    protected Path getBasePath() throws Exception {
        return RequireGCSConfiguration.getBasePath();
    }

    @Override
    protected FileSystemKind getFileSystemKind() {
        return FileSystemKind.OBJECT_STORE;
    }
}
