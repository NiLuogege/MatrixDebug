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

#ifndef WECHAT_BACKTRACE_PREDEFINED_H
#define WECHAT_BACKTRACE_PREDEFINED_H

#define DEFINE_STATIC_LOCAL(type, name, arguments) \
  static type& name = *new type arguments

#define DEFINE_STATIC_CPP_FIELD(type, name, arguments) \
  type& name = *new type arguments

#define BACKTRACE_EXPORT __attribute__ ((visibility ("default")))

#define BACKTRACE_FUNC_WRAPPER(fn) fn

#if defined(__LP64__)
#define ElfW(type) Elf64_ ## type
#else
#define ElfW(type) Elf32_ ## type
#endif

#endif //WECHAT_BACKTRACE_PREDEFINED_H
