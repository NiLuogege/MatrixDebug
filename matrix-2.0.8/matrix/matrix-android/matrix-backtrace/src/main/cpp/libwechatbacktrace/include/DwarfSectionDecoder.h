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

#ifndef _LIBUNWINDSTACK_WECHAT_QUICKEN_DWARF_SECTION_DECODER_H
#define _LIBUNWINDSTACK_WECHAT_QUICKEN_DWARF_SECTION_DECODER_H

#include <deque>
#include <map>
#include <unwindstack/Memory.h>
#include <unwindstack/DwarfStructs.h>
#include <unwindstack/DwarfLocation.h>
#include <unwindstack/DwarfMemory.h>
#include <unwindstack/DwarfError.h>
#include <unwindstack/Regs.h>

#include "Log.h"
#include "Errors.h"

namespace wechat_backtrace {

    template<typename AddressType>
    struct ValueExpression {
        AddressType value;
        uint16_t reg_expression;
    };

    typedef std::vector<uint64_t> QutInstrCollection;
    typedef std::map<uint64_t, std::pair<uint64_t, std::shared_ptr<QutInstrCollection>>> QutInstructionsOfEntries;

    template<typename AddressType>
    class DwarfSectionDecoder {

    public:
        DwarfSectionDecoder(unwindstack::Memory *memory);

        virtual ~DwarfSectionDecoder() = default;

        virtual bool Init(uint64_t offset, uint64_t size, int64_t section_bias);

        virtual const unwindstack::DwarfFde *GetFdeFromPc(uint64_t pc);

        virtual bool GetCfaLocationInfo(uint64_t pc, const unwindstack::DwarfFde *fde,
                                        unwindstack::dwarf_loc_regs_t *loc_regs);

        virtual uint64_t GetCieOffsetFromFde32(uint32_t pointer) = 0;

        virtual uint64_t GetCieOffsetFromFde64(uint64_t pointer) = 0;

        virtual uint64_t AdjustPcFromFde(uint64_t pc) = 0;

        void IterateAllEntries(
                QuickenGenerationContext &context,
                unwindstack::Memory *process_memory,
                /* out */ QutInstructionsOfEntries *);

        bool ParseSingleFde(
                QuickenGenerationContext &context,
                const unwindstack::DwarfFde *fde,
                const uint64_t pc,
                const bool iterate_loc,
                unwindstack::Memory *process_memory,
                /* out */ QutInstructionsOfEntries *all_instructions);

        bool Eval(const QuickenGenerationContext &,
                  const unwindstack::DwarfCie *, unwindstack::Memory *,
                  const unwindstack::dwarf_loc_regs_t &);

        const unwindstack::DwarfCie *GetCieFromOffset(uint64_t offset);

        const unwindstack::DwarfFde *GetFdeFromOffset(uint64_t offset);

        DwarfErrorCode LastErrorCode() { return last_error_.code; }

        uint64_t LastErrorAddress() { return last_error_.address; }

    protected:

        void FillFdes();

        bool GetNextCieOrFde(const unwindstack::DwarfFde **fde_entry);

        unwindstack::DwarfMemory memory_;

        uint32_t cie32_value_ = 0;
        uint64_t cie64_value_ = 0;

        std::unordered_map<uint64_t, unwindstack::DwarfFde> fde_entries_;
        std::unordered_map<uint64_t, unwindstack::DwarfCie> cie_entries_;
        std::unordered_map<uint64_t, unwindstack::dwarf_loc_regs_t> cie_loc_regs_;
        std::map<uint64_t, unwindstack::dwarf_loc_regs_t> loc_regs_;  // Single row indexed by pc_end.

        bool EvalRegister(const unwindstack::DwarfLocation *loc, uint16_t regs_total, uint32_t reg,
                          void *info);

        bool
        EvalExpression(const unwindstack::DwarfLocation &loc, unwindstack::Memory *regular_memory,
                       uint16_t regs_total, ValueExpression<AddressType> *value_expression,
                       bool *is_dex_pc);

        bool FillInCieHeader(unwindstack::DwarfCie *cie);

        bool FillInCie(unwindstack::DwarfCie *cie);

        bool FillInFdeHeader(unwindstack::DwarfFde *fde);

        bool FillInFde(unwindstack::DwarfFde *fde);

        void InsertFde(const unwindstack::DwarfFde *fde);

        bool CfaOffsetInstruction(
                const QuickenGenerationContext &context, const uint64_t reg, const uint64_t value);

        bool RegOffsetInstruction(uint64_t reg, uint64_t value);

        int64_t section_bias_ = 0;
        uint64_t entries_offset_ = 0;
        uint64_t entries_end_ = 0;
        uint64_t next_entries_offset_ = 0;
        uint64_t pc_offset_ = 0;

        std::map<uint64_t, std::pair<uint64_t, const unwindstack::DwarfFde *>> fdes_;

        DwarfErrorData last_error_{DWARF_ERROR_NONE, 0};

        const bool log = false;
        const uptr log_pc = 0;
//        bool log = false;
//        uptr log_pc = 0x13f994;

        std::shared_ptr<QutInstrCollection> temp_instructions_;
    };

}  // namespace wechat_backtrace

#endif  // _LIBUNWINDSTACK_WECHAT_QUICKEN_DWARF_SECTION_DECODER_H
