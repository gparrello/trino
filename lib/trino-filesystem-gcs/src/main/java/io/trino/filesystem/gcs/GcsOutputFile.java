/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.filesystem.gcs;

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.Storage.BlobTargetOption;
import com.google.cloud.storage.StorageException;
import io.airlift.slice.Slice;
import io.trino.filesystem.Location;
import io.trino.filesystem.TrinoOutputFile;
import io.trino.memory.context.AggregatedMemoryContext;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.file.FileAlreadyExistsException;

import static com.google.common.base.Preconditions.checkArgument;
import static io.trino.filesystem.gcs.GcsUtils.getBlob;
import static io.trino.filesystem.gcs.GcsUtils.handleGcsException;
import static java.util.Objects.requireNonNull;

public class GcsOutputFile
        implements TrinoOutputFile
{
    private static final BlobTargetOption[] DOES_NOT_EXIST_TARGET_OPTION = {BlobTargetOption.doesNotExist()};
    private static final BlobTargetOption[] EMPTY_TARGET_OPTIONS = {};

    private final GcsLocation location;
    private final Storage storage;
    private final long writeBlockSizeBytes;

    public GcsOutputFile(GcsLocation location, Storage storage, long writeBlockSizeBytes)
    {
        this.location = requireNonNull(location, "location is null");
        this.storage = requireNonNull(storage, "storage is null");
        checkArgument(writeBlockSizeBytes >= 0, "writeBlockSizeBytes is negative");
        this.writeBlockSizeBytes = writeBlockSizeBytes;
    }

    @Override
    public OutputStream create(AggregatedMemoryContext memoryContext)
            throws IOException
    {
        return createOutputStream(memoryContext, false);
    }

    @Override
    public OutputStream createOrOverwrite(AggregatedMemoryContext memoryContext)
            throws IOException
    {
        return createOutputStream(memoryContext, true);
    }

    @Override
    public void createExclusive(Slice content, AggregatedMemoryContext memoryContext)
            throws IOException
    {
        try {
            if (getBlob(storage, location).isPresent()) {
                throw new FileAlreadyExistsException("File %s already exists".formatted(location));
            }
            storage.create(
                    BlobInfo.newBuilder(BlobId.of(location.bucket(), location.path())).build(),
                    content.getBytes(),
                    DOES_NOT_EXIST_TARGET_OPTION);
        }
        catch (FileAlreadyExistsException e) {
            throw e;
        }
        catch (StorageException e) {
            // When the file corresponding to `location` already exists, the operation will fail with the exception message `412 Precondition Failed`
            if (e.getCode() == HttpURLConnection.HTTP_PRECON_FAILED) {
                throw new FileAlreadyExistsException(location.toString());
            }
            throw handleGcsException(e, "writing file", location);
        }
        catch (RuntimeException e) {
            throw handleGcsException(e, "writing file", location);
        }
    }

    private OutputStream createOutputStream(AggregatedMemoryContext memoryContext, boolean overwrite)
            throws IOException
    {
        try {
            BlobTargetOption[] blobTargetOptions = EMPTY_TARGET_OPTIONS;
            if (!overwrite) {
                if (getBlob(storage, location).isPresent()) {
                    throw new FileAlreadyExistsException("File %s already exists".formatted(location));
                }
                blobTargetOptions = DOES_NOT_EXIST_TARGET_OPTION;
            }
            Blob blob = storage.create(
                    BlobInfo.newBuilder(BlobId.of(location.bucket(), location.path())).build(),
                    blobTargetOptions);

            return new GcsOutputStream(location, blob, memoryContext, writeBlockSizeBytes);
        }
        catch (FileAlreadyExistsException e) {
            throw e;
        }
        catch (RuntimeException e) {
            throw handleGcsException(e, "writing file", location);
        }
    }

    @Override
    public Location location()
    {
        return location.location();
    }
}
