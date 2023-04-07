/*
 * Copyright (C) 2007 The Guava Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * This package contains generic collection interfaces and implementations, and
 * other utilities for working with collections. It is a part of the open-source
 * <a href="http://guava-libraries.googlecode.com">Guava libraries</a>.
 *
 * <h2>Collection Types</h2>
 *
 * <dl>
 * <dt>{@link com.squareup.haha.guava.collect.BiMap}
 * <dd>An extension of {@link java.util.Map} that guarantees the uniqueness of
 *     its values as well as that of its keys. This is sometimes called an
 *     "invertible map," since the restriction on values enables it to support
 *     an {@linkplain com.squareup.haha.guava.collect.BiMap#inverse inverse view} --
 *     which is another instance of {@code BiMap}.
 *
 * <dt>{@link com.squareup.haha.guava.collect.Multiset}
 * <dd>An extension of {@link java.util.Collection} that may contain duplicate
 *     values like a {@link java.util.List}, yet has order-independent equality
 *     like a {@link java.util.Set}.  One typical use for a multiset is to
 *     represent a histogram.
 *
 * <dt>{@link com.squareup.haha.guava.collect.Multimap}
 * <dd>A new type, which is similar to {@link java.util.Map}, but may contain
 *     multiple entries with the same key. Some behaviors of
 *     {@link com.squareup.haha.guava.collect.Multimap} are left unspecified and are
 *     provided only by the subtypes mentioned below.
 *
 * <dt>{@link com.squareup.haha.guava.collect.ListMultimap}
 * <dd>An extension of {@link com.squareup.haha.guava.collect.Multimap} which permits
 *     duplicate entries, supports random access of values for a particular key,
 *     and has <i>partially order-dependent equality</i> as defined by
 *     {@link com.squareup.haha.guava.collect.ListMultimap#equals(Object)}. {@code
 *     ListMultimap} takes its name from the fact that the {@linkplain
 *     com.squareup.haha.guava.collect.ListMultimap#get collection of values}
 *     associated with a given key fulfills the {@link java.util.List} contract.
 *
 * <dt>{@link com.squareup.haha.guava.collect.SetMultimap}
 * <dd>An extension of {@link com.squareup.haha.guava.collect.Multimap} which has
 *     order-independent equality and does not allow duplicate entries; that is,
 *     while a key may appear twice in a {@code SetMultimap}, each must map to a
 *     different value.  {@code SetMultimap} takes its name from the fact that
 *     the {@linkplain com.squareup.haha.guava.collect.SetMultimap#get collection of
 *     values} associated with a given key fulfills the {@link java.util.Set}
 *     contract.
 *
 * <dt>{@link com.squareup.haha.guava.collect.SortedSetMultimap}
 * <dd>An extension of {@link com.squareup.haha.guava.collect.SetMultimap} for which
 *     the {@linkplain com.squareup.haha.guava.collect.SortedSetMultimap#get
 *     collection values} associated with a given key is a
 *     {@link java.util.SortedSet}.
 *
 * <dt>{@link com.squareup.haha.guava.collect.Table}
 * <dd>A new type, which is similar to {@link java.util.Map}, but which indexes
 *     its values by an ordered pair of keys, a row key and column key.
 *
 * <dt>{@link com.squareup.haha.guava.collect.ClassToInstanceMap}
 * <dd>An extension of {@link java.util.Map} that associates a raw type with an
 *     instance of that type.
 * </dl>
 *
 * <h2>Collection Implementations</h2>
 *
 * <h3>of {@link java.util.List}</h3>
 * <ul>
 * <li>{@link com.squareup.haha.guava.collect.ImmutableList}
 * </ul>
 *
 * <h3>of {@link java.util.Set}</h3>
 * <ul>
 * <li>{@link com.squareup.haha.guava.collect.ImmutableSet}
 * <li>{@link com.squareup.haha.guava.collect.ImmutableSortedSet}
 * <li>{@link com.squareup.haha.guava.collect.ContiguousSet} (see {@code Range})
 * </ul>
 *
 * <h3>of {@link java.util.Map}</h3>
 * <ul>
 * <li>{@link com.squareup.haha.guava.collect.ImmutableMap}
 * <li>{@link com.squareup.haha.guava.collect.ImmutableSortedMap}
 * <li>{@link com.squareup.haha.guava.collect.MapMaker}
 * </ul>
 *
 * <h3>of {@link com.squareup.haha.guava.collect.BiMap}</h3>
 * <ul>
 * <li>{@link com.squareup.haha.guava.collect.ImmutableBiMap}
 * <li>{@link com.squareup.haha.guava.collect.HashBiMap}
 * <li>{@link com.squareup.haha.guava.collect.EnumBiMap}
 * <li>{@link com.squareup.haha.guava.collect.EnumHashBiMap}
 * </ul>
 *
 * <h3>of {@link com.squareup.haha.guava.collect.Multiset}</h3>
 * <ul>
 * <li>{@link com.squareup.haha.guava.collect.ImmutableMultiset}
 * <li>{@link com.squareup.haha.guava.collect.HashMultiset}
 * <li>{@link com.squareup.haha.guava.collect.LinkedHashMultiset}
 * <li>{@link com.squareup.haha.guava.collect.TreeMultiset}
 * <li>{@link com.squareup.haha.guava.collect.EnumMultiset}
 * <li>{@link com.squareup.haha.guava.collect.ConcurrentHashMultiset}
 * </ul>
 *
 * <h3>of {@link com.squareup.haha.guava.collect.Multimap}</h3>
 * <ul>
 * <li>{@link com.squareup.haha.guava.collect.ImmutableMultimap}
 * <li>{@link com.squareup.haha.guava.collect.ImmutableListMultimap}
 * <li>{@link com.squareup.haha.guava.collect.ImmutableSetMultimap}
 * <li>{@link com.squareup.haha.guava.collect.ArrayListMultimap}
 * <li>{@link com.squareup.haha.guava.collect.HashMultimap}
 * <li>{@link com.squareup.haha.guava.collect.TreeMultimap}
 * <li>{@link com.squareup.haha.guava.collect.LinkedHashMultimap}
 * <li>{@link com.squareup.haha.guava.collect.LinkedListMultimap}
 * </ul>
 *
 * <h3>of {@link com.squareup.haha.guava.collect.Table}</h3>
 * <ul>
 * <li>{@link com.squareup.haha.guava.collect.ImmutableTable}
 * <li>{@link com.squareup.haha.guava.collect.ArrayTable}
 * <li>{@link com.squareup.haha.guava.collect.HashBasedTable}
 * <li>{@link com.squareup.haha.guava.collect.TreeBasedTable}
 * </ul>
 *
 * <h3>of {@link com.squareup.haha.guava.collect.ClassToInstanceMap}</h3>
 * <ul>
 * <li>{@link com.squareup.haha.guava.collect.ImmutableClassToInstanceMap}
 * <li>{@link com.squareup.haha.guava.collect.MutableClassToInstanceMap}
 * </ul>
 *
 * <h2>Classes of static utility methods</h2>
 *
 * <ul>
 * <li>{@link com.squareup.haha.guava.collect.Collections2}
 * <li>{@link com.squareup.haha.guava.collect.Iterators}
 * <li>{@link com.squareup.haha.guava.collect.Iterables}
 * <li>{@link com.squareup.haha.guava.collect.Lists}
 * <li>{@link com.squareup.haha.guava.collect.Maps}
 * <li>{@link com.squareup.haha.guava.collect.Queues}
 * <li>{@link com.squareup.haha.guava.collect.Sets}
 * <li>{@link com.squareup.haha.guava.collect.Multisets}
 * <li>{@link com.squareup.haha.guava.collect.Multimaps}
 * <li>{@link com.squareup.haha.guava.collect.Tables}
 * <li>{@link com.squareup.haha.guava.collect.ObjectArrays}
 * </ul>
 *
 * <h2>Comparison</h2>
 *
 * <ul>
 * <li>{@link com.squareup.haha.guava.collect.Ordering}
 * <li>{@link com.squareup.haha.guava.collect.ComparisonChain}
 * </ul>
 *
 * <h2>Abstract implementations</h2>
 *
 * <ul>
 * <li>{@link com.squareup.haha.guava.collect.AbstractIterator}
 * <li>{@link com.squareup.haha.guava.collect.AbstractSequentialIterator}
 * <li>{@link com.squareup.haha.guava.collect.ImmutableCollection}
 * <li>{@link com.squareup.haha.guava.collect.UnmodifiableIterator}
 * <li>{@link com.squareup.haha.guava.collect.UnmodifiableListIterator}
 * </ul>
 *
 * <h2>Ranges</h2>
 *
 * <ul>
 * <li>{@link com.squareup.haha.guava.collect.Range}
 * <li>{@link com.squareup.haha.guava.collect.RangeMap}
 * <li>{@link com.squareup.haha.guava.collect.DiscreteDomain}
 * <li>{@link com.squareup.haha.guava.collect.ContiguousSet}
 * </ul>
 *
 * <h2>Other</h2>
 *
 * <ul>
 * <li>{@link com.squareup.haha.guava.collect.Interner},
 *     {@link com.squareup.haha.guava.collect.Interners}
 * <li>{@link com.squareup.haha.guava.collect.Constraint},
 *     {@link com.squareup.haha.guava.collect.Constraints}
 * <li>{@link com.squareup.haha.guava.collect.MapConstraint},
 *     {@link com.squareup.haha.guava.collect.MapConstraints}
 * <li>{@link com.squareup.haha.guava.collect.MapDifference},
 *     {@link com.squareup.haha.guava.collect.SortedMapDifference}
 * <li>{@link com.squareup.haha.guava.collect.MinMaxPriorityQueue}
 * <li>{@link com.squareup.haha.guava.collect.PeekingIterator}
 * </ul>
 *
 * <h2>Forwarding collections</h2>
 *
 * <ul>
 * <li>{@link com.squareup.haha.guava.collect.ForwardingCollection}
 * <li>{@link com.squareup.haha.guava.collect.ForwardingConcurrentMap}
 * <li>{@link com.squareup.haha.guava.collect.ForwardingIterator}
 * <li>{@link com.squareup.haha.guava.collect.ForwardingList}
 * <li>{@link com.squareup.haha.guava.collect.ForwardingListIterator}
 * <li>{@link com.squareup.haha.guava.collect.ForwardingListMultimap}
 * <li>{@link com.squareup.haha.guava.collect.ForwardingMap}
 * <li>{@link com.squareup.haha.guava.collect.ForwardingMapEntry}
 * <li>{@link com.squareup.haha.guava.collect.ForwardingMultimap}
 * <li>{@link com.squareup.haha.guava.collect.ForwardingMultiset}
 * <li>{@link com.squareup.haha.guava.collect.ForwardingNavigableMap}
 * <li>{@link com.squareup.haha.guava.collect.ForwardingNavigableSet}
 * <li>{@link com.squareup.haha.guava.collect.ForwardingObject}
 * <li>{@link com.squareup.haha.guava.collect.ForwardingQueue}
 * <li>{@link com.squareup.haha.guava.collect.ForwardingSet}
 * <li>{@link com.squareup.haha.guava.collect.ForwardingSetMultimap}
 * <li>{@link com.squareup.haha.guava.collect.ForwardingSortedMap}
 * <li>{@link com.squareup.haha.guava.collect.ForwardingSortedMultiset}
 * <li>{@link com.squareup.haha.guava.collect.ForwardingSortedSet}
 * <li>{@link com.squareup.haha.guava.collect.ForwardingSortedSetMultimap}
 * <li>{@link com.squareup.haha.guava.collect.ForwardingTable}
 * </ul>
 */
@com.annotation.ParametersAreNonnullByDefault
package com.squareup.haha.guava.collect;
