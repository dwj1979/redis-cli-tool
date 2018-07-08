package com.moilioncircle.redis.cli.tool.glossary;

import com.moilioncircle.redis.cli.tool.conf.Configure;
import com.moilioncircle.redis.cli.tool.ext.CliRedisReplicator;
import com.moilioncircle.redis.cli.tool.ext.rdt.BackupRdbVisitor;
import com.moilioncircle.redis.cli.tool.ext.rdt.MergeRdbVisitor;
import com.moilioncircle.redis.cli.tool.ext.rdt.SplitRdbVisitor;
import com.moilioncircle.redis.cli.tool.io.FilesOutputStream;
import com.moilioncircle.redis.cli.tool.util.OutputStreams;
import com.moilioncircle.redis.replicator.RedisURI;
import com.moilioncircle.redis.replicator.Replicator;
import com.moilioncircle.redis.replicator.io.CRCOutputStream;
import com.moilioncircle.redis.replicator.io.RedisInputStream;

import java.io.File;
import java.io.FileInputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.moilioncircle.redis.replicator.FileType.RDB;

/**
 * @author Baoyi Chen
 */
public enum Action {
    NONE,
    SPLIT,
    MERGE,
    BACKUP;
    
    public List<Replicator> dress(Configure configure, String split, String backup, List<File> merge, String output, List<Long> db, List<String> regexs, File conf, List<DataType> types) throws Exception {
        switch (this) {
            case MERGE:
                if (merge.isEmpty()) return Arrays.asList();
                CRCOutputStream out = OutputStreams.newCRCOutputStream(output);
                List<Replicator> list = new ArrayList<>();
                int max = 0;
                for (File file : merge) {
                    try (RedisInputStream in = new RedisInputStream(new FileInputStream(file))) {
                        in.skip(5); // skip REDIS
                        max = Math.max(max, Integer.parseInt(in.readString(4)));
                    }
                }
                // header
                out.write("REDIS".getBytes());
                out.write((max < 10 ? "000" + max : "00" + max).getBytes());
                // body
                for (File file : merge) {
                    URI u = file.toURI();
                    RedisURI mergeUri = new RedisURI(new URI("redis", u.getRawAuthority(), u.getRawPath(), u.getRawQuery(), u.getRawFragment()).toString());
                    if (mergeUri.getFileType() == null || mergeUri.getFileType() != RDB) {
                        throw new UnsupportedOperationException("Invalid options: --merge <file file...> must be rdb file.");
                    }
                    Replicator r = new CliRedisReplicator(mergeUri, configure);
                    r.setRdbVisitor(new MergeRdbVisitor(r, configure, db, regexs, types, () -> out));
                    list.add(r);
                }
                // tail
                list.get(list.size() - 1).addCloseListener(r -> {
                    OutputStreams.writeQuietly((byte) 255, out);
                    OutputStreams.writeQuietly(out.getCRC64(), out);
                    OutputStreams.closeQuietly(out);
                });
                return list;
            case SPLIT:
                Replicator r = new CliRedisReplicator(split, configure);
                r.setRdbVisitor(new SplitRdbVisitor(r, configure, db, regexs, types, () -> new FilesOutputStream(output, conf)));
                return Arrays.asList(r);
            case BACKUP:
                RedisURI backupUri = new RedisURI(backup);
                if (backupUri.getFileType() != null) {
                    throw new UnsupportedOperationException("Invalid options: --backup <uri> must be 'redis://host:port'.");
                }
                r = new CliRedisReplicator(backup, configure);
                r.setRdbVisitor(new BackupRdbVisitor(r, configure, db, regexs, types, () -> OutputStreams.newCRCOutputStream(output)));
                return Arrays.asList(r);
            case NONE:
                return Arrays.asList();
            default:
                throw new AssertionError(this);
        }
    }
}