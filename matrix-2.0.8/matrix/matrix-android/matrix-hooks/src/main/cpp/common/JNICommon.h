/*
 * Tencent is pleased to support the open source community by making wechat-matrix available.
 * Copyright (C) 2021 THL A29 Limited, a Tencent company. All rights reserved.
 * Licensed under the BSD 3-Clause License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

//
// Created by Yves on 2020-03-16.
//

#ifndef LIBMATRIX_HOOK_JNICOMMON_H
#define LIBMATRIX_HOOK_JNICOMMON_H

#include <jni.h>
#include <bits/pthread_types.h>

#ifdef __cplusplus
extern "C" {
#endif

extern JavaVM *m_java_vm;

extern jclass m_class_HookManager;
extern jmethodID m_method_getStack;

#ifdef __cplusplus
}
#endif

#endif //LIBMATRIX_HOOK_JNICOMMON_H
