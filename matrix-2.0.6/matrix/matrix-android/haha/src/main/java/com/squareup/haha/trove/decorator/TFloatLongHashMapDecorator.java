///////////////////////////////////////////////////////////////////////////////
// Copyright (c) 2002, Eric D. Friedman All Rights Reserved.
//
// This library is free software; you can redistribute it and/or
// modify it under the terms of the GNU Lesser General Public
// License as published by the Free Software Foundation; either
// version 2.1 of the License, or (at your option) any later version.
//
// This library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public
// License along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
///////////////////////////////////////////////////////////////////////////////
// THIS FILE IS AUTOGENERATED, PLEASE DO NOT EDIT OR ELSE
package com.squareup.haha.trove.decorator;

import com.squareup.haha.trove.TFloatLongHashMap;
import com.squareup.haha.trove.TFloatLongIterator;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.Map;
import java.util.Set;

/**
 * Wrapper class to make a TFloatLongHashMap conform to the <tt>java.util.Map</tt> API.
 * This class simply decorates an underlying TFloatLongHashMap and translates the Object-based
 * APIs into their Trove primitive analogs.
 * <p/>
 * <p/>
 * Note that wrapping and unwrapping primitive values is extremely inefficient.  If
 * possible, users of this class should override the appropriate methods in this class
 * and use a table of canonical values.
 * </p>
 * <p/>
 * Created: Mon Sep 23 22:07:40 PDT 2002
 *
 * @author Eric D. Friedman
 * @since trove 0.1.8
 */
public class TFloatLongHashMapDecorator extends AbstractMap<Float, Long> {
    /**
     * the wrapped primitive map
     */
    protected final TFloatLongHashMap _map;

    /**
     * Creates a wrapper that decorates the specified primitive map.
     */
    public TFloatLongHashMapDecorator(TFloatLongHashMap map) {
        super();
        this._map = map;
    }

    /**
     * Inserts a key/value pair into the map.
     *
     * @param key   an <code>Object</code> value
     * @param value an <code>Object</code> value
     * @return the previous value associated with <tt>key</tt>,
     *         or Long(0) if none was found.
     */
    @Override
    public Long put(Float key, Long value) {
        return wrapValue(_map.put(unwrapKey(key), unwrapValue(value)));
    }

    /**
     * Compares this map with another map for equality of their stored
     * entries.
     *
     * @param other an <code>Object</code> value
     * @return true if the maps are identical
     */
    @Override
    public boolean equals(Object other) {
        if (_map.equals(other)) {
            return true;	// comparing two trove maps
        } else if (other instanceof Map) {
            Map that = (Map) other;
            if (that.size() != _map.size()) {
                return false;	// different sizes, no need to compare
            } else {		// now we have to do it the hard way
                Iterator it = that.entrySet().iterator();
                for (int i = that.size(); i-- > 0;) {
                    Map.Entry e = (Map.Entry) it.next();
                    Object key = e.getKey();
                    Object val = e.getValue();
                    if (key instanceof Float && val instanceof Long) {
                        float k = unwrapKey(key);
                        long v = unwrapValue(val);
                        if (_map.containsKey(k) && v == _map.get(k)) {
                            // match, ok to continue
                        } else {
                            return false; // no match: we're done
                        }
                    } else {
                        return false; // different type in other map
                    }
                }
                return true;	// all entries match
            }
        } else {
            return false;
        }
    }

    @Override
    public Long get(Object object) {
        return get((Float)object);
    }

    /**
     * Retrieves the value for <tt>key</tt>
     *
     * @param key an <code>Object</code> value
     * @return the value of <tt>key</tt> or null if no such mapping exists.
     */
    public Long get(Float key) {
        float k = unwrapKey(key);
        long v = _map.get(k);
        // 0 may be a false positive since primitive maps
        // cannot return null, so we have to do an extra
        // check here.
        if (v == 0) {
            return _map.containsKey(k) ? wrapValue(v) : null;
        } else {
            return wrapValue(v);
        }
    }


    /**
     * Empties the map.
     */
    @Override
    public void clear() {
        this._map.clear();
    }

    /**
     * Deletes a key/value pair from the map.
     *
     * @param key an <code>Object</code> value
     * @return the removed value, or Long(0) if it was not found in the map
     */
    public Long remove(Float key) {
        return wrapValue(_map.remove(unwrapKey(key)));
    }
    @Override
    public Long remove(Object object) {
        return remove((Long)object);
    }

    /**
     * Returns a Set view on the entries of the map.
     *
     * @return a <code>Set</code> value
     */
    @Override
    public Set<Map.Entry<Float,Long>> entrySet() {
        return new AbstractSet<Map.Entry<Float,Long>>() {
            @Override
            public int size() {
                return _map.size();
            }
            @Override
            public boolean isEmpty() {
                return TFloatLongHashMapDecorator.this.isEmpty();
            }

            @Override
            public boolean contains(Object o) {
                if (o instanceof Map.Entry) {
                    Object k = ((Map.Entry) o).getKey();
                    Object v = ((Map.Entry) o).getValue();
                    return TFloatLongHashMapDecorator.this.containsKey(k)
                            && TFloatLongHashMapDecorator.this.get(k).equals(v);
                } else {
                    return false;
                }
            }

            @Override
            public Iterator<Map.Entry<Float,Long>> iterator() {
                return new Iterator<Map.Entry<Float,Long>>() {
                    private final TFloatLongIterator it = _map.iterator();

                    @Override
                    public Map.Entry<Float,Long> next() {
                        it.advance();
                        final Float key = wrapKey(it.key());
                        final Long v = wrapValue(it.value());
                        return new Map.Entry<Float,Long>() {
                            private Long val = v;

                            @Override
                            public boolean equals(Object o) {
                                return o instanceof Map.Entry
                                        && ((Map.Entry) o).getKey().equals(key)
                                        && ((Map.Entry) o).getValue().equals(val);
                            }

                            @Override
                            public Float getKey() {
                                return key;
                            }

                            @Override
                            public Long getValue() {
                                return val;
                            }

                            @Override
                            public int hashCode() {
                                return key.hashCode() + val.hashCode();
                            }

                            @Override
                            public Long setValue(Long value) {
                                val = value;
                                return put(key, value);
                            }
                        };
                    }

                    @Override
                    public boolean hasNext() {
                        return it.hasNext();
                    }

                    @Override
                    public void remove() {
                        it.remove();
                    }
                };
            }

            @Override
            public boolean add(Map.Entry<Float,Long> o) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean remove(Object o) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean addAll(Collection<? extends Map.Entry<Float, Long>> c) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean retainAll(Collection<?> c) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean removeAll(Collection<?> c) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void clear() {
                TFloatLongHashMapDecorator.this.clear();
            }
        };
    }

    /**
     * Checks for the presence of <tt>val</tt> in the values of the map.
     *
     * @param val an <code>Object</code> value
     * @return a <code>boolean</code> value
     */
    @Override
    public boolean containsValue(Object val) {
        return _map.containsValue(unwrapValue(val));
    }

    /**
     * Checks for the present of <tt>key</tt> in the keys of the map.
     *
     * @param key an <code>Object</code> value
     * @return a <code>boolean</code> value
     */
    @Override
    public boolean containsKey(Object key) {
        return _map.containsKey(unwrapKey(key));
    }

    /**
     * Returns the number of entries in the map.
     *
     * @return the map's size.
     */
    @Override
    public int size() {
        return this._map.size();
    }

    /**
     * Indicates whether map has any entries.
     *
     * @return true if the map is empty
     */
    @Override
    public boolean isEmpty() {
        return size() == 0;
    }

    /**
     * Copies the key/value mappings in <tt>map</tt> into this map.
     * Note that this will be a <b>deep</b> copy, as storage is by
     * primitive value.
     *
     * @param map a <code>Map</code> value
     */
    @Override
    public void putAll(Map<? extends Float, ? extends Long> map) {
        Iterator<? extends Entry<? extends Float,? extends Long>> it = map.entrySet().iterator();
        for (int i = map.size(); i-- > 0;) {
            Entry<? extends Float,? extends Long> e = it.next();
            this.put(e.getKey(), e.getValue());
        }
    }

    /**
     * Wraps a key
     *
     * @param k key in the underlying map
     * @return an Object representation of the key
     */
    protected Float wrapKey(float k) {
        return new Float(k);
    }

    /**
     * Unwraps a key
     *
     * @param key wrapped key
     * @return an unwrapped representation of the key
     */
    protected float unwrapKey(Object key) {
        return ((Float)key).floatValue();
    }

    /**
     * Wraps a value
     *
     * @param k value in the underlying map
     * @return an Object representation of the value
     */
    protected Long wrapValue(long k) {
        return new Long(k);
    }

    /**
     * Unwraps a value
     *
     * @param value wrapped value
     * @return an unwrapped representation of the value
     */
    protected long unwrapValue(Object value) {
        return ((Long)value).longValue();
    }

} // TFloatLongHashMapDecorator
