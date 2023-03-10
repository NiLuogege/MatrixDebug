/*
 * Tencent is pleased to support the open source community by making wechat-matrix available.
 * Copyright (C) 2019 THL A29 Limited, a Tencent company. All rights reserved.
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

#import "WCMemoryRecordManager.h"
#import "MatrixPathUtil.h"
#import "MatrixLogDef.h"
#import "dyld_image_info.h"

@interface WCMemoryRecordManager () {
    NSMutableArray *m_recordList;
    MemoryRecordInfo *m_currRecord;
}

@end

@implementation WCMemoryRecordManager

- (id)init {
    self = [super init];
    if (self) {
        [self loadRecordList];
    }
    return self;
}

- (NSArray *)recordList {
    if (m_recordList.count > 0) {
        [self sortRecordList];
        // Filter the record of this startup
        NSMutableArray *retList = [NSMutableArray arrayWithArray:m_recordList];
        [retList removeObject:m_currRecord];
        return retList;
    } else {
        return [NSArray array];
    }
}

- (MemoryRecordInfo *)getRecordByLaunchTime:(uint64_t)launchTime {
    for (MemoryRecordInfo *record in m_recordList) {
        if (record.launchTime == launchTime) {
            return record;
        }
    }
    return nil;
}

- (void)insertNewRecord:(MemoryRecordInfo *)record {
    m_currRecord = record;
    [m_recordList addObject:record];
    [self saveRecordList];
}

- (void)updateRecord:(MemoryRecordInfo *)record {
    for (int i = 0; i < m_recordList.count; ++i) {
        MemoryRecordInfo *tmpRecord = m_recordList[i];
        if (record.launchTime == tmpRecord.launchTime) {
            [m_recordList replaceObjectAtIndex:i withObject:record];
            [self saveRecordList];
            break;
        }
    }
}

- (void)deleteRecord:(MemoryRecordInfo *)record {
    for (int i = 0; i < m_recordList.count; ++i) {
        MemoryRecordInfo *tmpRecord = m_recordList[i];
        if (record.launchTime == tmpRecord.launchTime) {
            [m_recordList removeObjectAtIndex:i];
            [self saveRecordList];

            NSString *eventPath = [record recordDataPath];
            [[NSFileManager defaultManager] removeItemAtPath:eventPath error:NULL];
            break;
        }
    }
}

- (void)deleteAllRecords {
    for (int i = 0; i < m_recordList.count; ++i) {
        MemoryRecordInfo *record = m_recordList[i];
        NSString *eventPath = [record recordDataPath];
        [[NSFileManager defaultManager] removeItemAtPath:eventPath error:NULL];
    }

    [m_recordList removeAllObjects];
    [self saveRecordList];
}

- (void)sortRecordList {
    NSArray *sortedList = [m_recordList sortedArrayUsingComparator:^NSComparisonResult(id _Nonnull obj1, id _Nonnull obj2) {
        MemoryRecordInfo *info1 = (MemoryRecordInfo *)obj1;
        MemoryRecordInfo *info2 = (MemoryRecordInfo *)obj2;

        if (info1.launchTime < info2.launchTime) {
            return NSOrderedDescending;
        } else {
            return NSOrderedAscending;
        }
    }];

    m_recordList = [NSMutableArray arrayWithArray:sortedList];
}

- (void)loadRecordList {
    @try {
        m_recordList = [NSKeyedUnarchiver unarchiveObjectWithFile:[self recordListPath]];
        if ([m_recordList isKindOfClass:[NSArray class]] == NO) {
            m_recordList = nil;
        } else {
            m_recordList = [[NSMutableArray alloc] initWithArray:m_recordList];
        }
    } @catch (NSException *exception) {
        MatrixError(@"loadRecordList fail, %@", exception);
    } @finally {
        if (m_recordList == nil) {
            m_recordList = [NSMutableArray array];
        }
    }

    // delete records whose 'appUUID' not equal to curr uuid
    NSString *appUUID = @(app_uuid());
    NSMutableArray *removed = [NSMutableArray array];
    for (int i = 0; i < m_recordList.count; ++i) {
        MemoryRecordInfo *record = m_recordList[i];
        if (appUUID && [appUUID isEqualToString:record.appUUID] == NO) {
            NSString *eventPath = [record recordDataPath];
            [[NSFileManager defaultManager] removeItemAtPath:eventPath error:NULL];
            [removed addObject:record];
        }
    }
    if (removed.count > 0) {
        [m_recordList removeObjectsInArray:removed];
        [self saveRecordList];
    }

#define RECORD_MAX_COUNT 2

    // just limit N record
    if (m_recordList.count > RECORD_MAX_COUNT) {
        [self sortRecordList];

        for (int i = RECORD_MAX_COUNT; i < m_recordList.count; ++i) {
            MemoryRecordInfo *record = m_recordList[i];
            NSString *eventPath = [record recordDataPath];
            [[NSFileManager defaultManager] removeItemAtPath:eventPath error:NULL];
        }
        [m_recordList removeObjectsInRange:NSMakeRange(RECORD_MAX_COUNT, m_recordList.count - RECORD_MAX_COUNT)];

        [self saveRecordList];
    }
}

- (void)saveRecordList {
    @try {
        [NSKeyedArchiver archiveRootObject:m_recordList toFile:[self recordListPath]];
    } @catch (NSException *exception) {
        MatrixError(@"saveRecordList fail, %@", exception);
    }
}

- (NSString *)recordListPath {
    NSString *pathComponent = @"RecordList.dat";
    return [[MatrixPathUtil memoryStatPluginCachePath] stringByAppendingPathComponent:pathComponent];
}

@end
