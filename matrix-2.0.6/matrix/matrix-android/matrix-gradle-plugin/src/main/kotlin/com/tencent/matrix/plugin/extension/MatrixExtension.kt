/*
 * Tencent is pleased to support the open source community by making wechat-matrix available.
 * Copyright (C) 2018 THL A29 Limited, a Tencent company. All rights reserved.
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

package com.tencent.matrix.plugin.extension

/**
 * Created by caichongyang on 2017/6/20.
 */

open class MatrixExtension(
        var clientVersion: String = "",
        var uuid: String = "",
        var output: String = "",
        var logLevel: String = "I"
) {

    override fun toString(): String {
        return """| log vevel = $logLevel
//                  | uuid = $uuid
                """.trimMargin()
    }
}