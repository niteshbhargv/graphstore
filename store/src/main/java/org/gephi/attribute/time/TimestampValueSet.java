/*
 * Copyright 2012-2013 Gephi Consortium
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.gephi.attribute.time;

import java.util.Arrays;

/**
 *
 * @author mbastian
 */
public abstract class TimestampValueSet<T> {

    protected int[] array;
    protected int size = 0;

    public TimestampValueSet() {
        array = new int[0];
    }

    public TimestampValueSet(int capacity) {
        array = new int[capacity];
        Arrays.fill(array, -1);
    }

    public abstract void put(int timestampIndex, T value);

    public abstract void remove(int timestampIndex);

    public abstract T get(int timestampIndex);

    public abstract T[] toArray();

    protected int putInner(int timestampIndex) {
        int index = Arrays.binarySearch(array, 0, size, timestampIndex);
        if (index < 0) {
            int insertIndex = -index - 1;

            if (size < array.length - 1) {
                if (insertIndex < size) {
                    System.arraycopy(array, insertIndex, array, insertIndex + 1, size - insertIndex);
                }
                array[insertIndex] = timestampIndex;
            } else {
                int[] newArray = new int[array.length + 1];
                System.arraycopy(array, 0, newArray, 0, insertIndex);
                System.arraycopy(array, insertIndex, newArray, insertIndex + 1, array.length - insertIndex);
                newArray[insertIndex] = timestampIndex;
                array = newArray;
            }

            size++;
            return insertIndex;
        }
        return index;
    }

    protected int removeInner(int timestampIndex) {
        int index = Arrays.binarySearch(array, 0, size, timestampIndex);
        if (index >= 0) {
            int removeIndex = index;

            if (removeIndex == size - 1) {
                size--;
            } else {
                System.arraycopy(array, removeIndex + 1, array, removeIndex, size - removeIndex - 1);
                size--;
            }

            return removeIndex;
        }
        return -1;
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    protected int getIndex(int timestampIndex) {
        return Arrays.binarySearch(array, timestampIndex);
    }

    public boolean contains(int timestampIndex) {
        int index = Arrays.binarySearch(array, timestampIndex);
        return index >= 0 && index < size;
    }

    public int[] getTimestamps() {
        if (size < array.length - 1) {
            int[] res = new int[size];
            System.arraycopy(array, 0, res, 0, size);
            return res;
        } else {
            return array;
        }
    }

    public void clear() {
        size = 0;
        array = new int[0];
    }
}
