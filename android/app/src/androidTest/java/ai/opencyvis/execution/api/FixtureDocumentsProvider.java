package ai.opencyvis.execution.api;

import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Test APK process deliberately uses Android/Java only: Kotlin lives in the target APK. */
public final class FixtureDocumentsProvider extends DocumentsProvider {
    public static final byte[] BYTES = new byte[] {0, 1, -1, 2, 0, 10, 13, 42};
    private static final String[] COLUMNS = new String[] {Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS};

    public static File fixtureRoot(Context context) {
        return new File(context.getFilesDir(), "m4-document-fixture");
    }

    @Override public boolean onCreate() { return true; }

    @Override public Cursor queryRoots(String[] projection) {
        String[] columns = projection != null ? projection : new String[] {Root.COLUMN_ROOT_ID,
                Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS, Root.COLUMN_MIME_TYPES};
        MatrixCursor cursor = new MatrixCursor(columns);
        Map<String, Object> values = new HashMap<>();
        values.put(Root.COLUMN_ROOT_ID, "m4-test");
        values.put(Root.COLUMN_DOCUMENT_ID, "root");
        values.put(Root.COLUMN_TITLE, "M4 test documents");
        values.put(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE);
        values.put(Root.COLUMN_MIME_TYPES, "*/*");
        addRow(cursor, values);
        return cursor;
    }

    @Override public Cursor queryDocument(String documentId, String[] projection) {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : COLUMNS);
        addDocument(cursor, documentId);
        return cursor;
    }

    @Override public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder) {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : COLUMNS);
        File[] children = file(parentDocumentId).listFiles();
        if (children != null) {
            Arrays.sort(children, (first, second) -> first.getName().compareTo(second.getName()));
            for (File child : children) addDocument(cursor, parentDocumentId + "/" + child.getName());
        }
        return cursor;
    }

    @Override public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        return ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.parseMode(mode));
    }

    @Override public String createDocument(String parentDocumentId, String mimeType, String displayName) {
        requireName(displayName);
        File parent = file(parentDocumentId);
        if (!parent.isDirectory()) throw new IllegalArgumentException("Parent is not a directory");
        String name = displayName;
        int suffix = 1;
        while (new File(parent, name).exists()) name = displayName + " (" + suffix++ + ")";
        File created = new File(parent, name);
        try {
            boolean success = Document.MIME_TYPE_DIR.equals(mimeType) ? created.mkdir() : created.createNewFile();
            if (!success) throw new IllegalStateException("Cannot create fixture document");
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
        return parentDocumentId + "/" + name;
    }

    @Override public String renameDocument(String documentId, String displayName) {
        requireName(displayName);
        File source = file(documentId);
        if (source.equals(fixtureRoot(getContext()))) throw new IllegalArgumentException("Cannot rename fixture root");
        File target = new File(source.getParentFile(), displayName);
        if (target.exists() || !source.renameTo(target)) throw new IllegalStateException("Cannot rename fixture document");
        return documentId.substring(0, documentId.lastIndexOf('/')) + "/" + displayName;
    }

    @Override public void deleteDocument(String documentId) { deleteTree(file(documentId)); }

    @Override public boolean isChildDocument(String parentDocumentId, String documentId) {
        return file(documentId).getPath().startsWith(file(parentDocumentId).getPath() + File.separator);
    }

    private File file(String documentId) {
        if (!documentId.equals("root") && !documentId.startsWith("root/")) throw new IllegalArgumentException("Invalid fixture document ID");
        try {
            File root = fixtureRoot(getContext()).getCanonicalFile();
            File result = documentId.equals("root") ? root : new File(root, documentId.substring(5)).getCanonicalFile();
            if (!result.equals(root) && !result.getPath().startsWith(root.getPath() + File.separator)) {
                throw new IllegalArgumentException("Document leaves fixture directory");
            }
            return result;
        } catch (IOException error) {
            throw new IllegalArgumentException(error);
        }
    }

    private void addDocument(MatrixCursor cursor, String documentId) {
        File value = file(documentId);
        if (!value.exists()) throw new IllegalArgumentException("Fixture document does not exist");
        int flags = value.isDirectory() ? Document.FLAG_DIR_SUPPORTS_CREATE :
                Document.FLAG_SUPPORTS_WRITE | Document.FLAG_SUPPORTS_RENAME | Document.FLAG_SUPPORTS_DELETE;
        Map<String, Object> data = new HashMap<>();
        data.put(Document.COLUMN_DOCUMENT_ID, documentId);
        data.put(Document.COLUMN_DISPLAY_NAME, value.getName());
        data.put(Document.COLUMN_MIME_TYPE, value.isDirectory() ? Document.MIME_TYPE_DIR : "application/octet-stream");
        data.put(Document.COLUMN_SIZE, value.length());
        data.put(Document.COLUMN_LAST_MODIFIED, value.lastModified());
        data.put(Document.COLUMN_FLAGS, flags);
        addRow(cursor, data);
    }

    private static void addRow(MatrixCursor cursor, Map<String, Object> values) {
        String[] columns = cursor.getColumnNames();
        Object[] row = new Object[columns.length];
        for (int index = 0; index < columns.length; index++) row[index] = values.get(columns[index]);
        cursor.addRow(row);
    }

    private static void requireName(String name) {
        if (name == null || name.trim().isEmpty() || name.contains("/") || name.contains("\\")) {
            throw new IllegalArgumentException("Invalid fixture document name");
        }
    }

    static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        if (file.exists() && !file.delete()) throw new IllegalStateException("Cannot delete fixture file: " + file);
    }
}
