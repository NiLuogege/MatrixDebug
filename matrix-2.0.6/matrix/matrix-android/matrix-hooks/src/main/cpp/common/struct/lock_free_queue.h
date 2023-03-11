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

#ifndef lock_free_queue_h
#define lock_free_queue_h

#include <cstdint>

#include <common/Macros.h>

namespace matrix {

    template <typename T>
    struct TaggedPointer {
        T * ptr_ = nullptr;
        size_t tag_ = 0;

        TaggedPointer(void) {}

        TaggedPointer(TaggedPointer const & p) = default;

        explicit TaggedPointer(T * p = nullptr, size_t t = 0): ptr_(p), tag_(t) {}

        bool operator== (volatile TaggedPointer const & p) const {
            return (ptr_ == p.ptr_) && (tag_ == p.tag_);
        }

        bool operator!= (volatile TaggedPointer const & p) const {
            return !operator==(p);
        }
    };

    template<typename T>
    struct Node {
        T t_;
        std::atomic<TaggedPointer<Node<T>>> next_;
    };

    template<typename T>
    class ObjectStorage {
    public:
        ObjectStorage() = default;
        virtual ~ObjectStorage() = default;
        virtual T *provide() = 0;
    };

    template<typename T, size_t Reserved>
    class FixedObjectStorage : public ObjectStorage<T> {
    public:
        FixedObjectStorage() = default;

        virtual ~FixedObjectStorage() = default;

        inline T *provide() override {
            size_t idx = available_.fetch_add(1);
            if (UNLIKELY(idx >= Reserved)) {
                available_.store(Reserved);
                return nullptr;
            }
            return &objects[idx];
        }

    private:
        T objects[Reserved]{0};
        std::atomic<size_t> available_ = 0;
    };

    template<typename T, size_t AugmentExp, size_t MaxFold>
    class ResizableObjectStorage : public ObjectStorage<T> {
    public:
        ResizableObjectStorage() : max_fold_(MaxFold), size_augment_exp_(AugmentExp),
                                   size_augment_(1 << AugmentExp), mask_(size_augment_ - 1) {

            objects_ = static_cast<T **>(calloc(max_fold_, sizeof(void *)));

            resize(size_);
        };

        virtual ~ResizableObjectStorage() {

            for (size_t i = 0; i < max_fold_; i++) {
                if (objects_[i]) {
                    free(objects_[i]);
                }
            }
            free(objects_);
        };

        inline T *provide() override {
            size_t idx = available_.fetch_add(1);
            size_t size = size_.load(std::memory_order_acquire);
            if (UNLIKELY(idx >= size)) {
                while (resize(size)) {
                    size = size_.load(std::memory_order_acquire);
                    if (idx < size) {
                        return get(idx);
                    } else {
                        continue;
                    }
                }
                return nullptr;
            }
            return get(idx);
        }

        inline size_t size() {
            return size_.load(std::memory_order_relaxed);
        }

        inline size_t usage() {
            return available_.load(std::memory_order_relaxed);
        }

    private:

        inline T *get(const size_t idx) {

            size_t array = idx >> size_augment_exp_;
            size_t offset = idx & mask_;

            HOOK_CHECK(array < current_fold_);
            HOOK_CHECK(offset < size_augment_);

            T *object = &objects_[array][offset];
            return object;
        }

        inline bool resize(size_t from_size) {

            if (current_fold_ >= max_fold_) return false;
            if (from_size < size_) return true;

            std::lock_guard<std::mutex> guard(resize_lock_);

            if (current_fold_ >= max_fold_) return false;
            if (from_size < size_) return true;

            auto buffer = static_cast<T *>(calloc(size_augment_, sizeof(T)));

            if (buffer) {

                objects_[current_fold_++] = buffer;
                size_ += size_augment_;

                return true;
            } else {
                return false;
            }
        }

        T **objects_ = nullptr;

        std::atomic<size_t> available_ = 0;
        std::atomic<size_t> size_ = 0;
        std::atomic<size_t> current_fold_ = 0;

        const size_t max_fold_;
        const size_t size_augment_;
        const size_t size_augment_exp_;
        const size_t mask_;

        std::mutex resize_lock_;

    };

    template<typename T>
    class FreeList {
    public:
        FreeList() {
            free_.store(nullptr);
        }

        virtual ~FreeList() = default;

        Node<T> *allocate() {
            for (;;) {
                auto free_node = free_.load(std::memory_order_acquire);

                if (!free_node) {
                    return storage()->provide();
                }

                auto next_tagged_ptr = free_node->next_.load(std::memory_order_acquire);
                auto next_node = next_tagged_ptr.ptr_;
                if (free_.compare_exchange_weak(free_node, next_node)) {
                    memset(free_node, 0, sizeof(Node<T>));
                    return free_node;
                }
            }

        }

        void deallocate(Node<T> * node) {

            if (UNLIKELY(!node)) return;

            for (;;) {
                auto free_node = free_.load(std::memory_order_acquire);
                TaggedPointer<Node<T>> free_tagged_ptr(free_node, 0);
                node->next_.store(free_tagged_ptr);
                if (free_.compare_exchange_weak(free_node, node)) {
                    return;
                }
            }
        }

        virtual ObjectStorage<Node<T>> *storage() = 0;

    private:

        std::atomic<Node<T> *> free_;

    };

    template<typename T, size_t Reserved>
    class FixedFreeList : public FreeList<T> {
    public:
        FixedFreeList() = default;

        virtual ~FixedFreeList() = default;

        ObjectStorage<Node<T>> *storage() override {
            return &storage_;
        }

    private:

        FixedObjectStorage<Node<T>, Reserved> storage_;
    };


    template<typename T, size_t AugmentExp, size_t MaxFold>
    class ResizableFreeList : public FreeList<T> {
    public:
        ResizableFreeList() = default;

        virtual ~ResizableFreeList() = default;

        ObjectStorage<Node<T>> *storage() override {
            return &storage_;
        }

    private:

        ResizableObjectStorage<Node<T>, AugmentExp, MaxFold> storage_;
    };

    template<typename T>
    class LockFreeQueue {
    public:
        LockFreeQueue(FreeList<T> *free_list) {

            HOOK_CHECK(free_list);

            free_list_ = free_list;
            TaggedPointer dummy(free_list_->allocate(), 1);
            head_.store(dummy);
            tail_.store(dummy);
        }

        ~LockFreeQueue() = default;

        bool offer(const T & t) {

            HOOK_CHECK(t)

            auto new_node = free_list_->allocate();
            if (UNLIKELY(!new_node)) return false;
            new_node->t_ = t;

            HOOK_CHECK(!new_node->next_.load().ptr_)
            HOOK_CHECK(!new_node->next_.load().tag_)

            for (;;) {
                auto tail = tail_.load(std::memory_order_acquire);
                auto tail_node = tail.ptr_;
                auto next = tail_node->next_.load(std::memory_order_acquire);
                auto next_node = next.ptr_;

                auto tail_2 = tail_.load(std::memory_order_acquire);
                if (LIKELY(tail == tail_2)) {
                    if (next_node == nullptr) {
                        TaggedPointer<Node<T>> new_tail_next(new_node, next.tag_ + 1);
                        if (tail_node->next_.compare_exchange_weak(next, new_tail_next)) {
                            TaggedPointer<Node<T>> new_tail(new_node, tail.tag_ + 1);
                            tail_.compare_exchange_strong(tail, new_tail);
                            return true;
                        }
                    } else {
                        TaggedPointer<Node<T>> new_tail(next_node, tail.tag_ + 1);
                        tail_.compare_exchange_strong(tail, new_tail);
                    }
                }
            }
        }

        bool poll(T & ret) {

            for (;;) {
                auto head = head_.load(std::memory_order_acquire);
                auto head_node = head.ptr_;

                auto tail = tail_.load(std::memory_order_acquire);

                auto next = head_node->next_.load(std::memory_order_acquire);
                auto next_node = next.ptr_;

                auto head_2 = head_.load(std::memory_order_acquire);
                if (LIKELY(head == head_2)) {
                    if (head == tail) {
                        if (next_node == nullptr)
                            return false;
                        TaggedPointer<Node<T>> new_tail(next_node, tail.tag_ + 1);
                        tail_.compare_exchange_strong(tail, new_tail);
                    } else {
                        if (next_node == nullptr)
                            continue;

                        ret = next_node->t_;
                        TaggedPointer<Node<T>> new_head(next_node, head.tag_ + 1);
                        if (head_.compare_exchange_weak(head, new_head)) {
                            free_list_->deallocate(head_node);
                            HOOK_CHECK(ret)
                            return true;
                        }
                    }
                }
            }
        }

    private:

        std::atomic<TaggedPointer<Node<T>>> head_;
        std::atomic<TaggedPointer<Node<T>>> tail_;

        FreeList<T> *free_list_;
    };

}
#endif /* lock_free_queue_h */
