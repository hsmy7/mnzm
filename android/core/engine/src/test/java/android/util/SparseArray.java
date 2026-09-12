package android.util;

import java.util.Arrays;

/**
 * SparseArray 测试影子实现（仅普通 JVM 测试类路径）。
 *
 * ## 为什么需要
 *
 * Gradle unitTests `returnDefaultValues = true` 下，mockable android.jar 的
 * android.util.SparseArray 是**静默 no-op 桩**（put 丢弃、get 返回 null、
 * indexOfKey 恒 0）——DiscipleTables 的 String/对象列（ComponentTable<T>）在
 * 普通 JVM 测试下写入丢失、读取为 null（Int/Double 列用自研 IntFlatArray/
 * DoubleFlatArray 不受影响，这是 DiffAuthoritativeTickTest 等普通 JUnit
 * 对拍测试在 NPE 与部分失效间游走的根因）。
 *
 * 本文件在测试源集提供与 android.util.SparseArray 同包名的真实实现——
 * 测试类目录先于 mockable jar 被应用 ClassLoader 解析，普通 JVM 测试拿到
 * 真实现；Robolectric 沙箱类加载器优先加载 android-all 的真实现，两者互不
 * 干扰。语义与 Android SparseArray 对齐（key 升序二分、无符号补码插入点、
 * 重复 key 覆盖）。
 *
 * ## 覆盖范围
 *
 * 仅实现 DiscipleTables/ComponentTable 实际使用的成员
 * （put/get/get(key,default)/indexOfKey/size/keyAt/valueAt/setValueAt/
 * remove/delete/clear/clone）。新增使用面时按 Android 语义补全。
 */
public class SparseArray<E> implements Cloneable {

    private static final int[] EMPTY_KEYS = new int[0];
    private static final Object[] EMPTY_VALUES = new Object[0];

    private int[] mKeys;
    private Object[] mValues;
    private int mSize = 0;

    public SparseArray() {
        this(10);
    }

    public SparseArray(int initialCapacity) {
        if (initialCapacity <= 0) {
            mKeys = EMPTY_KEYS;
            mValues = EMPTY_VALUES;
        } else {
            mKeys = new int[initialCapacity];
            mValues = new Object[initialCapacity];
        }
    }

    public int size() {
        return mSize;
    }

    public E get(int key) {
        return get(key, null);
    }

    @SuppressWarnings("unchecked")
    public E get(int key, E valueIfKeyNotFound) {
        int i = indexOfKey(key);
        return i >= 0 ? (E) mValues[i] : valueIfKeyNotFound;
    }

    public void put(int key, E value) {
        int i = indexOfKey(key);
        if (i >= 0) {
            mValues[i] = value;
            return;
        }
        i = ~i;
        if (mSize >= mKeys.length) {
            grow();
        }
        if (i < mSize) {
            System.arraycopy(mKeys, i, mKeys, i + 1, mSize - i);
            System.arraycopy(mValues, i, mValues, i + 1, mSize - i);
        }
        mKeys[i] = key;
        mValues[i] = value;
        mSize++;
    }

    /** key 升序二分；命中返回下标，未命中返回插入点按位取反（Android 语义）。 */
    public int indexOfKey(int key) {
        int lo = 0;
        int hi = mSize - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int midKey = mKeys[mid];
            if (midKey < key) {
                lo = mid + 1;
            } else if (midKey > key) {
                hi = mid - 1;
            } else {
                return mid;
            }
        }
        return ~lo;
    }

    public void delete(int key) {
        remove(key);
    }

    public void remove(int key) {
        int i = indexOfKey(key);
        if (i >= 0) {
            System.arraycopy(mKeys, i + 1, mKeys, i, mSize - i - 1);
            System.arraycopy(mValues, i + 1, mValues, i, mSize - i - 1);
            mSize--;
            mValues[mSize] = null;
        }
    }

    public void clear() {
        Arrays.fill(mValues, 0, mSize, null);
        mSize = 0;
    }

    public int keyAt(int index) {
        return mKeys[index];
    }

    @SuppressWarnings("unchecked")
    public E valueAt(int index) {
        return (E) mValues[index];
    }

    public void setValueAt(int index, E value) {
        mValues[index] = value;
    }

    @Override
    @SuppressWarnings("unchecked")
    public SparseArray<E> clone() {
        SparseArray<E> clone;
        try {
            clone = (SparseArray<E>) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new AssertionError(e);
        }
        clone.mKeys = mKeys.clone();
        clone.mValues = mValues.clone();
        return clone;
    }

    private void grow() {
        int newCapacity = mKeys.length > 0 ? mKeys.length * 2 : 10;
        mKeys = Arrays.copyOf(mKeys, newCapacity);
        mValues = Arrays.copyOf(mValues, newCapacity);
    }
}
