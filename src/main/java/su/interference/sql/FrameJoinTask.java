/**
 The MIT License (MIT)

 Copyright (c) 2010-2021 head systems, ltd

 Permission is hereby granted, free of charge, to any person obtaining a copy of
 this software and associated documentation files (the "Software"), to deal in
 the Software without restriction, including without limitation the rights to
 use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 the Software, and to permit persons to whom the Software is furnished to do so,
 subject to the following conditions:

 The above copyright notice and this permission notice shall be included in all
 copies or substantial portions of the Software.

 THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

 */

package su.interference.sql;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import su.interference.core.*;
import su.interference.metrics.Metrics;
import su.interference.persistent.*;
import su.interference.api.GenericResult;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.ArrayList;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author Yuriy Glotanov
 * @since 1.0
 */

public class FrameJoinTask implements Callable<List<Object>> {

    private final static Logger logger = LoggerFactory.getLogger(FrameJoinTask.class);
    private final FrameApi bd1;
    private final FrameApi bd2;
    private final ResultSet target;
    private final List<SQLColumn> cols;
    private final NestedCondition nc;
    private final Session s;
    private final List<Object> res;
    private final int sqlcid;
    private final boolean last;
    private final static ConcurrentHashMap<String, Class> cache = new ConcurrentHashMap<String, Class>();
    private final FrameApiJoin j;
    private final SQLJoinDispatcher hmap;

    static {
        cache.put("int", int.class);
        cache.put("long", long.class);
        cache.put("float", float.class);
        cache.put("double", double.class);
    }

    public FrameJoinTask(Cursor cur, FrameApi bd1, FrameApi bd2, ResultSet target, List<SQLColumn> cols, NestedCondition nc, int sqlcid, int nodeId, boolean last, boolean leftfs, SQLJoinDispatcher hmap, FrameApiJoin j, Session s) {
        this.bd1 = bd1;
        this.bd2 = bd2;
        this.target = target;
        this.cols = cols;
        this.nc = nc;
        this.s = s;
        this.res = new ArrayList<Object>();
        this.sqlcid = sqlcid;
        this.last = last;
        this.j = j;
        this.hmap = hmap;
    }

    @SuppressWarnings("unchecked")
    public List<Object> call() throws Exception {
        final Thread thread = Thread.currentThread();
        thread.setName("interference-sql-join-task-" + thread.getId());
        final Class r = target instanceof ResultSetImpl ? ((ResultSetImpl)target).getTableClass() : target instanceof StreamQueue ? ((StreamQueue) target).getRstable().getSc() : null;
        final int t1 = bd1.getObjectId();
        final int t2 = bd2==null?0:bd2.getObjectId();
        final Class c1 = Instance.getInstance().getTableById(t1).getTableClass();
        final Class c2 = bd2==null?null:Instance.getInstance().getTableById(t2).getTableClass();
        ResultSetEntity rsa = (ResultSetEntity)c1.getAnnotation(ResultSetEntity.class);
        final boolean c1rs = rsa != null;

        if (bd2 != null && bd2.getImpl() == FrameApi.IMPL_INDEX && bd1.getImpl() == FrameApi.IMPL_INDEX) {
            if (((SQLIndexFrame) bd2).isMerged()) {
                if (hmap.getJoin() == SQLJoinDispatcher.RIGHT_MERGE) {
                    final ArrayList<Object> drs1 = bd1.getFrameEntities(s);
                    final ArrayList<Object> drs2 = bd2 == null ? null : bd2.getFrameEntities(s);
                    final int s1 = drs1.size();
                    final int s2 = drs2.size();
                    int p1 = 0;
                    int p2 = 0;
                    boolean cnue = true;
                    while (cnue) {
                        if (s1 > 0 && s2 > 0) {
                            final Comparable o1 = getKeyValue(drs1.get(p1), ((SQLIndexFrame) bd2).getLkey(), s);
                            final Comparable o2 = getKeyValue(drs2.get(p2), ((SQLIndexFrame) bd2).getRkey(), s);
                            final int cmp = o1.compareTo(o2);
                            if (cmp < 0) {
                                p1++;
                            } else if (cmp > 0) {
                                p2++;
                            } else {
                                Object j = null;
                                final IndexChunk ib1 = (IndexChunk) drs1.get(p1);
                                final IndexChunk ib2 = (IndexChunk) drs2.get(p2);
                                if (ib1.getDataChunk() == null) {
                                    logger.error(bd1.getAllocId() + " " + bd1.getFrameId() + " found ib1chunk = null, drs1.size = " + drs1.size() + " p1 = " + p1);
                                }
                                if (ib2.getDataChunk() == null) {
                                    logger.error(bd2.getAllocId() + " " + bd2.getFrameId() + " found ib2chunk = null, drs2.size = " + drs2.size() + " p2 = " + p2);
                                }
                                final Object oo1 = ib1.getDataChunk() == null ? null : ib1.getDataChunk().getEntity();
                                final Object oo2 = ib2.getDataChunk() == null ? null : ib2.getDataChunk().getEntity();
                                j = joinDataRecords(r, c1, c2, t1, t2, oo1, oo2, cols, c1rs, s);
                                if (hmap.skipCheckNC()) {
                                    res.add(j);
                                } else {
                                    if (nc.checkNC(j, sqlcid, last, s)) {
                                        res.add(j);
                                    }
                                }
                                final int n = p2 + 1;
                                if (n == s2) {
                                    p1++;
                                } else {
                                    final Comparable next = getKeyValue(drs2.get(n), ((SQLIndexFrame) bd2).getRkey(), s);
                                    if (o1.compareTo(next) < 0) {
                                        p1++;
                                    } else {
                                        p2++;
                                    }
                                }
                            }
                            if (p1 == s1 || p2 == s2) {
                                cnue = false;
                            }
                        } else {
                            cnue = false;
                        }
                    }
                } else if (hmap.getJoin() == SQLJoinDispatcher.MERGE) {
                    IndexChunk ib1 = (IndexChunk) ((SQLIndexFrame) bd1).poll(s);
                    IndexChunk ib2 = (IndexChunk) ((SQLIndexFrame) bd2).poll(s);
                    boolean cnue = true;
                    if (ib1 != null && ib2 != null) {
                        while (cnue) {
                            final Comparable o1 = getKeyValue(ib1, ((SQLIndexFrame) bd2).getLkey(), s);
                            final Comparable o2 = getKeyValue(ib2, ((SQLIndexFrame) bd2).getRkey(), s);
                            final int cmp = o1.compareTo(o2);
                            if (cmp < 0) {
                                ib1 = (IndexChunk) ((SQLIndexFrame) bd1).poll(s);
                            } else if (cmp > 0) {
                                ib2 = (IndexChunk) ((SQLIndexFrame) bd2).poll(s);
                            } else {
                                if (ib1.getDataChunk() == null) {
                                    logger.error(bd1.getAllocId() + " " + bd1.getFrameId() + " left merge chunk is null");
                                }
                                if (ib2.getDataChunk() == null) {
                                    logger.error(bd2.getAllocId() + " " + bd2.getFrameId() + " right merge chunk is null");
                                }
                                final Object oo1 = ib1.getDataChunk() == null ? null : ib1.getDataChunk().getEntity();
                                final Object oo2 = ib2.getDataChunk() == null ? null : ib2.getDataChunk().getEntity();
                                Object j = joinDataRecords(r, c1, c2, t1, t2, oo1, oo2, cols, c1rs, s);
                                if (hmap.skipCheckNC()) {
                                    res.add(j);
                                } else {
                                    if (nc.checkNC(j, sqlcid, last, s)) {
                                        res.add(j);
                                    }
                                }

                                IndexChunk nc = (IndexChunk) ((SQLIndexFrame) bd2).poll(s);
                                if (nc == null) {
                                    ib1 = (IndexChunk) ((SQLIndexFrame) bd1).poll(s);
                                } else {
                                    final Comparable next = getKeyValue(nc, ((SQLIndexFrame) bd2).getRkey(), s);
                                    ib2 = nc;

                                /*
                                if (o1.compareTo(next) < 0) {
                                    ib1 = (IndexChunk) ((SQLIndexFrame) bd1).poll(s);
                                } else {
                                    ib2 = nc;
                                }
*/
                                }
                            }
                            if (ib1 == null || ib2 == null) {
                                cnue = false;
                            }
                        }
                    }
                } else {
                    throw new RuntimeException("unknown MERGE type: " + hmap.getJoin());
                }
            }
        } else {
            final ArrayList<Object> drs1 = bd1.getFrameEntities(s);
            final ArrayList<Object> drs2 = bd2 == null ? null : bd2.getFrameEntities(s);

            for (Object o1 : drs1) {
                if (bd1.getImpl() == FrameApi.IMPL_INDEX) {
                    final IndexChunk ib1 = (IndexChunk) o1;
                    o1 = ib1.getDataChunk().getEntity();
                }
                if (drs2 == null) {
                    //HashFrame returns null drs
                    if (bd2 != null && bd2.getImpl() == FrameApi.IMPL_HASH) {
                        final Comparable key = getHashKeyValue(c1, o1, ((SQLHashMapFrame) bd2).getCkey(), s);
                        final List<Object> o2 = ((SQLHashMapFrame) bd2).get(key, s);
                        if (o2 != null) {
                            if (!(o2.get(0) == null && last)) {
                                Object j = joinDataRecords(r, c1, c2, t1, t2, o1, o2.get(0), cols, c1rs, s);
                                if (hmap.skipCheckNC()) {
                                    res.add(j);
                                } else {
                                    if (nc.checkNC(j, sqlcid, last, s)) {
                                        res.add(j);
                                    }
                                }
                            }
                        }
                    } else if (bd2 != null && bd2.getImpl() == FrameApi.IMPL_INDEX) {
                        final Comparable key = getHashKeyValue(c1, o1, ((SQLIndexFrame) bd2).getLkey(), s);
                        final List<Object> o2 = ((SQLIndexFrame) bd2).get(key, s);
                        //todo may cause wrong (cutted) resultsets in non-last cursors for (OR) conditions - see hashmap impl above
                        if (o2 != null) {
                            for (Object o2_ : o2) {
                                final IndexChunk ib = (IndexChunk) ((DataChunk) o2_).getEntity();
                                final Object oo2 = ib.getDataChunk().getEntity();
                                final Object j = joinDataRecords(r, c1, c2, t1, t2, o1, oo2, cols, c1rs, s);
                                if (hmap.skipCheckNC()) {
                                    res.add(j);
                                } else {
                                    if (nc.checkNC(j, sqlcid, last, s)) {
                                        res.add(j);
                                    }
                                }
                            }
                        }
                    } else {
                        // one table loop
                        // workaround for StreamQueue - always returns initial EntityContainer
                        if (r == null || target instanceof StreamQueue) {
                            //todo need to cast o1 to RS type
                            if (nc.checkNC(o1, sqlcid, last, s)) {
                                res.add(o1); //target table is null -> result class is null -> returns generic entities
                            }
                        } else {
                            Object j = joinDataRecords(r, c1, c2, t1, t2, o1, null, cols, c1rs, s);
                            if (nc.checkNC(j, sqlcid, last, s)) {
                                res.add(j);
                            }
                        }
                    }
                } else {
                    //nested loop
                    for (Object o2 : drs2) {
                        Object j = joinDataRecords(r, c1, c2, t1, t2, o1, o2, cols, c1rs, s);
                        if (nc.checkNC(j, sqlcid, last, s)) {
                            res.add(j);
                        }
                    }
                }
            }
        }
        Metrics.get("recordLCount").put(res.size());
        if (j != null) {
            j.setResult(res);
        }
        return res;
    }

    private Object joinDataRecords (Class r, Class c1, Class c2, int t1, int t2, Object o1, Object o2, List<SQLColumn> cols, boolean isrs, Session s)
        throws NoSuchMethodException, IllegalAccessException, InstantiationException, InvocationTargetException, ClassNotFoundException {
        final GenericResult ret = (GenericResult)r.newInstance();
        if (isrs) {
            for (SQLColumn sqlc : cols) {
                if (sqlc.getObjectId() == t1) {
                    final Method y = sqlc.getAliasGetter();
                    final Method z = sqlc.getSetter(r);
                    z.invoke(ret, new Object[]{y.invoke(o1, null)});
                }
            }
            if (c2!=null) {
                for (SQLColumn sqlc : cols) {
                    if (sqlc.getObjectId() == t2) {
                        final Method y = sqlc.getGetter();
                        final Method z = sqlc.getSetter(r);
                        z.invoke(ret, new Object[]{y.invoke(o2, new Object[]{s})});
                    }
                }
            }
        } else {
            for (SQLColumn sqlc : cols) {
                if (sqlc.getObjectId()==t1) {
                    final Method y = sqlc.getGetter();
                    final Method z = sqlc.getSetter(r);
                    z.invoke(ret, new Object[]{y.invoke(o1, new Object[]{s})});
                }
                if (c2 != null && o2 != null) {
                    if (sqlc.getObjectId() == t2) {
                        final Method y = sqlc.getGetter();
                        final Method z = sqlc.getSetter(r);
                        z.invoke(ret, new Object[]{y.invoke(o2, new Object[]{s})});
                    }
                }
            }
        }

        return ret;
    }

    private Comparable getKeyValue(Object o, SQLColumn sqlc, Session s) throws InvocationTargetException, IllegalAccessException {
        final Method y = sqlc.getKeyGetter();
        return sqlc.isCursor() ? (Comparable) y.invoke(o, null) : (Comparable) y.invoke(o, new Object[]{s});
    }

    private Comparable getHashKeyValue(Class c, Object o, SQLColumn sqlc, Session s) throws InvocationTargetException, IllegalAccessException {
        final Method y = sqlc.getGetter();
        return sqlc.isCursor() ? (Comparable) y.invoke(o, null) : (Comparable) y.invoke(o, new Object[]{s});
    }

}
