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

#ifndef _LIBWECHATBACKTRACE_QUICKEN_INTERFACE_H
#define _LIBWECHATBACKTRACE_QUICKEN_INTERFACE_H

#include <stdint.h>
#include <memory>
#include <mutex>

#include <unwindstack/Memory.h>
#include <unwindstack/Error.h>
#include "QuickenTableGenerator.h"
#include "Errors.h"
#include "QuickenMaps.h"
#include "QuickenUtility.h"
#include "QuickenInMemory.h"
#include "DebugJit.h"
#include "ElfWrapper.h"

namespace wechat_backtrace {

    class QuickenMapInfo;

    class QuickenInterface {

    public:
        QuickenInterface(uint64_t load_bias,
                         uint64_t elf_offset,
                         uint64_t elf_start_offset,
                         unwindstack::ArchEnum arch)
                : load_bias_(load_bias), elf_offset_(elf_offset),
                  elf_start_offset_(elf_start_offset),
                  arch_(arch) {}

        bool FindEntry(QutSections *qut_sections, uptr pc, size_t *entry_offset);

        bool StepJIT(StepContext *step_context, wechat_backtrace::Maps *maps);

        bool Step(StepContext *step_context);

        template<typename AddressType>
        bool GenerateQuickenTable(unwindstack::Memory *memory,
                                  unwindstack::Memory *gnu_debug_data_memory,
                                  unwindstack::Memory *process_memory,
                                  QutSectionsPtr qut_sections);

        QutFileError TryInitQuickenTable();

        uint64_t GetLoadBias() const;

        uint64_t GetElfOffset() const;

        uint64_t GetElfStartOffset() const;

        void SetArmExidxInfo(uint64_t start_offset, uint64_t total_entries) {
            arm_exidx_info_ = {start_offset, 0, total_entries};
        }

        void SetEhFrameHdrInfo(uint64_t offset, int64_t section_bias, uint64_t size) {
            eh_frame_hdr_info_ = {offset, section_bias, size};
        }

        void SetEhFrameInfo(uint64_t offset, int64_t section_bias, uint64_t size) {
            eh_frame_info_ = {offset, section_bias, size};
        }

        void SetDebugFrameInfo(uint64_t offset, int64_t section_bias, uint64_t size) {
            debug_frame_info_ = {offset, section_bias, size};
        }

        void SetGnuEhFrameHdrInfo(uint64_t offset, int64_t section_bias, uint64_t size) {
            gnu_eh_frame_hdr_info_ = {offset, section_bias, size};
        }

        void SetGnuEhFrameInfo(uint64_t offset, int64_t section_bias, uint64_t size) {
            gnu_eh_frame_info_ = {offset, section_bias, size};
        }

        void SetGnuDebugFrameInfo(uint64_t offset, int64_t section_bias, uint64_t size) {
            gnu_debug_frame_info_ = {offset, section_bias, size};
        }

        std::string &GetHash() {
            return hash_;
        }

        std::string &GetSoname() {
            return soname_;
        }

        void
        InitSoInfo(const std::string &sopath, const std::string &soname,
                   const std::string &build_id, const uint64_t elf_start_offset,
                   const bool jit_cache) {
            (void) soname;
            jit_cache_ = jit_cache;
            soname_ = jit_cache_ ? sopath : SplitSonameFromPath(sopath);
            sopath_ = sopath;
            build_id_ = build_id;
            hash_ = ToHash(
                    sopath_ + std::to_string(FileSize(sopath)) + std::to_string(elf_start_offset));
        }

        void InitDebugJit() {
            if (jit_cache_) {
                debug_jit_ = DebugJit::Instance();
            }
        }

        void FillQuickenInMemory(std::shared_ptr<unwindstack::Memory> &process_memory) {

            if (!quicken_in_memory_) {

                quicken_in_memory_ = std::make_shared<QuickenInMemory<addr_t>>();

                elf_wrapper_->FillQuickenInterface(this);

                quicken_in_memory_->Init(elf_wrapper_.get(), process_memory,
                        eh_frame_hdr_info_, eh_frame_info_, debug_frame_info_,
                        gnu_eh_frame_hdr_info_, gnu_eh_frame_info_,
                        gnu_debug_frame_info_, arm_exidx_info_);
            }
        }

        void ResetQuickenInMemory();

        static void
        SetQuickenGenerateDelegate(quicken_generate_delegate_func quicken_generate_delegate);

        QutErrorCode last_error_code_ = QUT_ERROR_NONE;
        size_t bad_entries_ = 0;

        const bool log = false;
        const uptr log_pc = 0;
//        const bool log = true;
//        const uptr log_pc = 0x598f9;

        bool jit_cache_ = false;
        std::shared_ptr<DebugJit> debug_jit_;

        std::shared_ptr<QuickenInMemory<addr_t>> quicken_in_memory_;

        std::shared_mutex lock_quicken_in_memory_;

        std::unique_ptr<ElfWrapper> elf_wrapper_;

    protected:

        std::string soname_;
        std::string sopath_;
        std::string build_id_;
        std::string hash_;

        uint64_t load_bias_ = 0;
        uint64_t elf_offset_ = 0;
        uint64_t elf_start_offset_ = 0;

        unwindstack::ArchEnum arch_;

        FrameInfo arm_exidx_info_ = {0};

        FrameInfo eh_frame_hdr_info_ = {0};
        FrameInfo eh_frame_info_ = {0};
        FrameInfo debug_frame_info_ = {0};

        FrameInfo gnu_eh_frame_hdr_info_ = {0};
        FrameInfo gnu_eh_frame_info_ = {0};
        FrameInfo gnu_debug_frame_info_ = {0};

        QutSections *qut_sections_ = nullptr;

        std::mutex lock_;

        bool StepInternal(StepContext *step_context, QutSections *sections);

        // TODO Should remove.
        size_t try_load_qut_failed_count_ = 0;
        static quicken_generate_delegate_func quicken_generate_delegate_;
    };

}  // namespace wechat_backtrace

#endif  // _LIBWECHATBACKTRACE_QUICKEN_INTERFACE_H
