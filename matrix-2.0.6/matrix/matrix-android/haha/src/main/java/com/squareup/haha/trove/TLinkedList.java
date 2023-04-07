///////////////////////////////////////////////////////////////////////////////
// Copyright (c) 2001, Eric D. Friedman All Rights Reserved.
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


package com.squareup.haha.trove;

import java.io.Serializable;
import java.util.AbstractSequentialList;
import java.util.ListIterator;
import java.util.NoSuchElementException;

/**
 * A LinkedList implementation which holds instances of type
 * <tt>TLinkable</tt>.
 *
 * <p>Using this implementation allows you to get java.util.LinkedList
 * behavior (a doubly linked list, with Iterators that support insert
 * and delete operations) without incurring the overhead of creating
 * <tt>Node</tt> wrapper objects for every element in your list.</p>
 *
 * <p>The requirement to achieve this time/space gain is that the
 * Objects stored in the List implement the <tt>TLinkable</tt>
 * interface.</p>
 *
 * <p>The limitations are that you cannot put the same object into
 * more than one list or more than once in the same list.  You must
 * also ensure that you only remove objects that are actually in the
 * list.  That is, if you have an object A and lists l1 and l2, you
 * must ensure that you invoke List.remove(A) on the correct list.  It
 * is also forbidden to invoke List.remove() with an unaffiliated
 * TLinkable (one that belongs to no list): this will destroy the list
 * you invoke it on.</p>
 *
 * <p>
 * Created: Sat Nov 10 15:25:10 2001
 * </p>
 *
 * @author Eric D. Friedman
 * @version $Id: TLinkedList.java,v 1.5 2004/09/24 09:11:15 cdr Exp $
 * @see TLinkable
 */

public class TLinkedList<T extends TLinkable> extends AbstractSequentialList<T> implements Serializable {

    /** the head of the list */
    protected T _head;
    /** the tail of the list */
    protected T _tail;
    /** the number of elements in the list */
    protected int _size;
    
    /**
     * Creates a new <code>TLinkedList</code> instance.
     *
     */
    public TLinkedList() {
        super();
    }

    /**
     * Returns an iterator positioned at <tt>index</tt>.  Assuming
     * that the list has a value at that index, calling next() will
     * retrieve and advance the iterator.  Assuming that there is a
     * value before <tt>index</tt> in the list, calling previous()
     * will retrieve it (the value at index - 1) and move the iterator
     * to that position.  So, iterating from front to back starts at
     * 0; iterating from back to front starts at <tt>size()</tt>.
     *
     * @param index an <code>int</code> value
     * @return a <code>ListIterator</code> value
     */
    @Override
    public ListIterator<T> listIterator(int index) {
        return new IteratorImpl(index);
    }

    /**
     * Returns the number of elements in the list.
     *
     * @return an <code>int</code> value
     */
    @Override
    public int size() {
        return _size;
    }

    /**
     * Inserts <tt>linkable</tt> at index <tt>index</tt> in the list.
     * All values > index are shifted over one position to accomodate
     * the new addition.
     *
     * @param index an <code>int</code> value
     * @param linkable an object of type TLinkable
     */
    @Override
    public void add(int index, T linkable) {
        if (index < 0 || index > size()) {
            throw new IndexOutOfBoundsException("index:" + index);
        }
        insert(index,linkable);
    }

    /**
     * Appends <tt>linkable</tt> to the end of the list.
     *
     * @param linkable an object of type TLinkable
     * @return always true
     */
    @Override
    public boolean add(T linkable) {
        insert(_size, linkable);
        return true;
    }

    /**
     * Inserts <tt>linkable</tt> at the head of the list.
     *
     * @param linkable an object of type TLinkable
     */
    public void addFirst(T linkable) {
        insert(0, linkable);
    }

    /**
     * Adds <tt>linkable</tt> to the end of the list.
     *
     * @param linkable an object of type TLinkable
     */
    public void addLast(T linkable) {
        insert(size(), linkable);
    }

    /**
     * Empties the list.
     *
     */
    @Override
    public void clear() {
        if (null != _head) {
            for (TLinkable link = _head.getNext();
                 link != null;
                 link = link.getNext()) {
                TLinkable prev = link.getPrevious();
                prev.setNext(null);
                link.setPrevious(null);
            }
            _head = _tail = null;
        }
        _size = 0;
    }

    /**
     * Copies the list's contents into a native array.  This will be a
     * shallow copy: the Tlinkable instances in the Object[] array
     * have links to one another: changing those will put this list
     * into an unpredictable state.  Holding a reference to one
     * element in the list will prevent the others from being garbage
     * collected unless you clear the next/previous links.  <b>Caveat
     * programmer!</b>
     *
     * @return an <code>Object[]</code> value
     */
    @Override
    public Object[] toArray() {
        Object[] o = new Object[_size];
        int i = 0;
        for (TLinkable link = _head; link != null; link = link.getNext()) {
            o[i++] = link;
        }
        return o;
    }

    /**
     * Copies the list to a native array, destroying the next/previous
     * links as the copy is made.  This list will be emptied after the
     * copy (as if clear() had been invoked).  The Object[] array
     * returned will contain TLinkables that do <b>not</b> hold
     * references to one another and so are less likely to be the
     * cause of memory leaks.
     *
     * @return an <code>Object[]</code> value
     */
    public Object[] toUnlinkedArray() {
        Object[] o = new Object[_size];
        int i = 0;
        for (T link = _head; link != null; i++) {
            o[i] = link;
            T tmp = link;
            link = (T) link.getNext();
            tmp.setNext(null); // clear the links
            tmp.setPrevious(null);
        }
        _size = 0;              // clear the list
        _head = _tail = null;
        return o;
    }

    /**
     * A linear search for <tt>o</tt> in the list.
     *
     * @param o an <code>Object</code> value
     * @return a <code>boolean</code> value
     */
    @Override
    public boolean contains(Object o) {
        for (TLinkable link = _head; link != null; link = link.getNext()) {
            if (o.equals(link)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the head of the list
     *
     * @return an <code>Object</code> value
     */
    public T getFirst() {
        return _head;
    }

    /**
     * Returns the tail of the list.
     *
     * @return an <code>Object</code> value
     */
    public T getLast() {
        return _tail;
    }

    /**
     * Remove and return the first element in the list.
     *
     * @return an <code>Object</code> value
     */
    public T removeFirst() {
        T o = _head;
        T n = (T) o.getNext();
        o.setNext(null);

        if (null != n) {
            n.setPrevious(null);
        }

        _head = n;
        if (--_size == 0) {
            _tail = null;
        }
        return o;
    }

    /**
     * Remove and return the last element in the list.
     *
     * @return an <code>Object</code> value
     */
    public T removeLast() {
        T o = _tail;
        T prev = (T) o.getPrevious();
        o.setPrevious(null);

        if (null != prev) {
            prev.setNext(null);
        }
        _tail = prev;
        if (--_size == 0) {
            _head = null;
        }
        return o;
    }

    /**
     * Implementation of index-based list insertions.
     *
     * @param index an <code>int</code> value
     * @param linkable an object of type TLinkable
     */
    protected void insert(int index, T linkable) {
        T newLink = linkable;

        if (_size == 0) {
            _head = _tail = newLink; // first insertion
        } else if (index == 0) {
            newLink.setNext(_head); // insert at front
            _head.setPrevious(newLink);
            _head = newLink;
        } else if (index == _size) { // insert at back
            _tail.setNext(newLink);
            newLink.setPrevious(_tail);
            _tail = newLink;
        } else {
            TLinkable prior;

            // looking at the size of the list, we decide whether
            // it's faster to reach `index' by traversing the
            // list from the front or the back.
            if (index > (_size >> 1)) { // insert in 2nd half
                // work from the tail
                int pos = _size -1;
                for (prior = _tail; pos > index; pos--) {
                    prior = prior.getPrevious();
                }
            } else {                // insert in 1st half
                // work from the head
                int pos = 0;
                for (prior = _head; pos < index; pos++) {
                    prior = prior.getNext();
                }
            }
            TLinkable post = prior.getNext();
            // insert newlink
            newLink.setNext(post);
            newLink.setPrevious(prior);
            // adjust adjacent pointers
            post.setPrevious(newLink);
            prior.setNext(newLink);
        }
        _size++;
    }

    /**
     * Removes the specified element from the list.  Note that
     * it is the caller's responsibility to ensure that the
     * element does, in fact, belong to this list and not another
     * instance of TLinkedList.  
     *
     * @param o a TLinkable element already inserted in this list.
     * @return true if the element was a TLinkable and removed
     */
    @Override
    public boolean remove(Object o) {
        if (o instanceof TLinkable) {
            TLinkable link = (TLinkable)o;

            T p = (T) link.getPrevious();
            T n = (T) link.getNext();

            if (n == null && p == null) { // emptying the list
                _head = _tail = null;
            } else if (n == null) { // this is the tail
                // make previous the new tail
                link.setPrevious(null);
                p.setNext(null);
                _tail = p;
            } else if (p == null) { // this is the head
                // make next the new head
                link.setNext(null);
                n.setPrevious(null);
                _head = n;
            } else {            // somewhere in the middle
                p.setNext(n);
                n.setPrevious(p);
                link.setNext(null);
                link.setPrevious(null);
            }

            _size--;            // reduce size of list
            return true;
        } else {
            return false;
        }
    }

    /**
     * Inserts newElement into the list immediately before current.
     * All elements to the right of and including current are shifted
     * over.
     *
     * @param current a <code>TLinkable</code> value currently in the list.
     * @param newElement a <code>TLinkable</code> value to be added to
     * the list.
     */
    public void addBefore(T current, T newElement) {
        if (current == _head) {
            addFirst(newElement);
        } else if (current == null) {
            addLast(newElement);
        } else {
            TLinkable p = current.getPrevious();
            newElement.setNext(current);
            p.setNext(newElement);
            newElement.setPrevious(p);
            current.setPrevious(newElement);
            _size++;
        }
    }

    /**
     * A ListIterator that supports additions and deletions.
     *
     */
    protected final class IteratorImpl implements ListIterator<T> {
        private int _nextIndex;
        private T _next;
        private T _lastReturned;

        /**
         * Creates a new <code>Iterator</code> instance positioned at
         * <tt>index</tt>.
         *
         * @param position an <code>int</code> value
         */
        IteratorImpl(int position) {
            if (position < 0 || position > _size) {
                throw new IndexOutOfBoundsException();
            }

            _nextIndex = position;
            if (position == 0) {
                _next = _head;
            } else if (position == _size) {
                _next = null;
            } else if (position < (_size >> 1)) {
                int pos = 0;
                for (_next = _head; pos < position; pos++) {
                    _next = (T) _next.getNext();
                }
            } else {
                int pos = _size - 1;
                for (_next = _tail; pos > position; pos--) {
                    _next = (T) _next.getPrevious();
                }
            }
        }
        
        /**
         * Insert <tt>linkable</tt> at the current position of the iterator.
         * Calling next() after add() will return the added object.
         *
         * @param linkable an object of type TLinkable
         */
        @Override
        public final void add(T linkable) {
            _lastReturned = null;
            _nextIndex++;

            if (_size == 0) {
                TLinkedList.this.add(linkable);
            } else {
                addBefore(_next, linkable);
            }
        }

        /**
         * True if a call to next() will return an object.
         *
         * @return a <code>boolean</code> value
         */
        @Override
        public final boolean hasNext() {
            return _nextIndex != _size;
        }

        /**
         * True if a call to previous() will return a value.
         *
         * @return a <code>boolean</code> value
         */
        @Override
        public final boolean hasPrevious() {
            return _nextIndex != 0;
        }

        /**
         * Returns the value at the Iterator's index and advances the
         * iterator.
         *
         * @return an <code>Object</code> value
         * @exception NoSuchElementException if there is no next element
         */
        @Override
        public final T next() {
            if (_nextIndex == _size) {
                throw new NoSuchElementException();
            }

            _lastReturned = _next;
            _next = (T) _next.getNext();
            _nextIndex++;
            return _lastReturned;
        }

        /**
         * returns the index of the next node in the list (the
         * one that would be returned by a call to next()).
         *
         * @return an <code>int</code> value
         */
        @Override
        public final int nextIndex() {
            return _nextIndex;
        }

        /**
         * Returns the value before the Iterator's index and moves the
         * iterator back one index.
         *
         * @return an <code>Object</code> value
         * @exception NoSuchElementException if there is no previous element.
         */
        @Override
        public final T previous() {
            if (_nextIndex == 0) {
                throw new NoSuchElementException();
            }

            if (_nextIndex == _size) {
                _lastReturned = _next = _tail;
            } else {
                _lastReturned = _next = (T) _next.getPrevious();
            }

            _nextIndex--;
            return _lastReturned;
        }

        /**
         * Returns the previous element's index.
         *
         * @return an <code>int</code> value
         */
        @Override
        public final int previousIndex() {
            return _nextIndex - 1;
        }

        /**
         * Removes the current element in the list and shrinks its
         * size accordingly.
         *
         * @exception IllegalStateException neither next nor previous
         * have been invoked, or remove or add have been invoked after
         * the last invocation of next or previous.
         */
        @Override
        public final void remove() {
            if (_lastReturned == null) {
                throw new IllegalStateException("must invoke next or previous before invoking remove");
            }

            if (_lastReturned != _next) {
                _nextIndex--;
            }
            _next = (T) _lastReturned.getNext();
            TLinkedList.this.remove(_lastReturned);
            _lastReturned = null;
        }

        /**
         * Replaces the current element in the list with
         * <tt>linkable</tt>
         *
         * @param linkable an object of type TLinkable
         */
        @Override
        public final void set(T linkable) {
            if (_lastReturned == null) {
                throw new IllegalStateException();
            }
            T l = linkable;

            // need to check both, since this could be the only
            // element in the list.
            if (_lastReturned == _head) {
                _head = l;
            }

            if (_lastReturned == _tail) {
                _tail = l;
            }

            swap(_lastReturned, l);
            _lastReturned = l;
        }

        /**
         * Replace from with to in the list.
         *
         * @param from a <code>TLinkable</code> value
         * @param to a <code>TLinkable</code> value
         */
        private void swap(T from, T to) {
            T p = (T) from.getPrevious();
            T n = (T) from.getNext();

            if (null != p) {
                to.setPrevious(p);
                p.setNext(to);
            }
            if (null != n) {
                to.setNext(n);
                n.setPrevious(to);
            }
            from.setNext(null);
            from.setPrevious(null);
        }
    }
} // TLinkedList
