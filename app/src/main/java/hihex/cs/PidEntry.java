/*
 * CatSaver
 * Copyright (C) 2015 HiHex Ltd.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package hihex.cs;

import android.util.SparseArray;

import com.google.common.base.Charsets;
import com.google.common.base.Optional;
import com.google.common.io.Closeables;
import com.google.common.io.Files;

import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.Date;
import java.util.Locale;

public final class PidEntry {
    private static final String UNSAFE_FILENAME_PATTERN = "[^-_.,;a-zA-Z0-9]";
    private static final String CONTINUED_SUFFIX = "( \\([^)]+\\))*\\.html\\.gz$";

    public final int pid;
    public final String processName;
    public final Optional<File> path;
    public final Optional<Writer> writer;
    private final SparseArray<String> mThreadNames;

    private PidEntry(final int pid, final String processName, final File path, final Writer writer, final SparseArray<String> threadNames) {
        this.pid = pid;
        this.processName = processName;
        this.path = Optional.of(path);
        this.writer = Optional.of(writer);
        mThreadNames = threadNames;
    }

    private PidEntry(final int pid, final String processName, final SparseArray<String> threadNames) {
        this.pid = pid;
        this.processName = processName;
        path = Optional.absent();
        writer = Optional.absent();
        mThreadNames = threadNames;
    }

    public PidEntry(final int pid, final String processName) {
        this(pid, processName, new SparseArray<String>());
    }

    /**
     * Close the file it is currently writing to.
     */
    public PidEntry close() {
        if (writer.isPresent()) {
            final Writer writer2 = writer.get();
            try {
                writer2.flush();
            } catch (final IOException e) {
                CsLog.e("Failed to flush file, some data may not be written. " + e);
            } finally {
                try {
                    Closeables.close(writer2, true);
                } catch (IOException e) {
                    // Ignore.
                }
            }
        }
        return new PidEntry(pid, processName, mThreadNames);
    }

    /**
     * Open a new file, using the timestamp to name the file if possible.
     */
    public PidEntry open(final LogFiles logFiles, final Date timestamp) throws IOException {
        if (writer.isPresent()) {
            return this;
        }

        final String safeName = processName.replaceAll(UNSAFE_FILENAME_PATTERN, "-");
        final String filePrefix = String.format(Locale.ROOT, "%2$s-%1$tF-%1$tH.%1$tM.%1$tS", timestamp, safeName);

        // We try "xxx.html.gz", "xxx (1).html.gz", "xxx (2).html.gz", ... until a filename is free to use.
        final File path = logFiles.getNewPath(filePrefix, ".html.gz");
        final Writer writer = new OutputStreamWriter(new FlushableGzipOutputStream(path), Charsets.UTF_8);

        return new PidEntry(pid, processName, path, writer, mThreadNames);
    }

    public PidEntry split(final LogFiles logFiles) throws IOException {
        if (!writer.isPresent()) {
            return this;
        }

        final String fileName = path.get().getName().replaceAll(CONTINUED_SUFFIX, "");
        final File newPath = logFiles.getNewPath(fileName + " (continued)", ".html.gz");
        final Writer newWriter = new OutputStreamWriter(new FlushableGzipOutputStream(newPath), Charsets.UTF_8);

        close();
        return new PidEntry(pid, processName, newPath, newWriter, mThreadNames);
    }

    public String getThreadName(final int tid) {
        final String cachedThreadName = mThreadNames.get(tid);
        if (cachedThreadName != null) {
            return cachedThreadName;
        }

        final File threadNameFile = new File("/proc/" + pid + "/task/" + tid + "/comm");
        String threadName;
        try {
            threadName = Files.toString(threadNameFile, Charsets.UTF_8);
        } catch (final IOException e) {
            threadName = "TID:" + tid;
        }
        mThreadNames.append(tid, threadName);

        return threadName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        PidEntry pidEntry = (PidEntry) o;

        return pid == pidEntry.pid && processName.equals(pidEntry.processName);
    }

    @Override
    public int hashCode() {
        int result = pid;
        result = 31 * result + processName.hashCode();
        return result;
    }
}
