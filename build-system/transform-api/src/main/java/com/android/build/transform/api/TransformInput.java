/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.build.transform.api;

import com.android.annotations.NonNull;
import com.google.common.annotations.Beta;

import java.io.File;
import java.util.Collection;
import java.util.Map;

/**
 * The input to a Transform
 */
@Beta
public interface TransformInput extends ScopedContent {

    /**
     * The file changed status for incremental execution.
     */
    enum FileStatus { ADDED, CHANGED, REMOVED }

    /**
     * Returns the files of the input.
     */
    @NonNull
    Collection<File> getFiles();

    /**
     * Returns the changed files. This is only valid if the transform is in incremental mode.
     * TODO: change this Map<> into a List<ChangedFile> with ChangedFile containing three
     * basic information (File, FileStatus and Folder containing all the files under consideration
     */
    @NonNull
    Map<File, FileStatus> getChangedFiles();
}
