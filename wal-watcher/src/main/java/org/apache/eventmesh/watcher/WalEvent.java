package org.apache.eventmesh.watcher;

import java.io.File;

public class WalEvent {
    // 1. String path or File  这里只需要关注具体的 wal 文件
    // 2. type: create, modify, delete
    // 3. 时间
    private File file;
    /** 0:create, 1:modify, 2:delete */
    private int type;
    private String time;

}
