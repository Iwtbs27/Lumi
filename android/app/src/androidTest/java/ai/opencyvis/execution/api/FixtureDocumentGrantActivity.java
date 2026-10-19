package ai.opencyvis.execution.api;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** Runs with the provider APK UID and grants real persisted URI access to the target APK. */
public final class FixtureDocumentGrantActivity extends Activity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String target = getIntent().getStringExtra("target_package");
        String authority = getIntent().getStringExtra("authority");
        if (target == null || authority == null) throw new IllegalArgumentException("Fixture target and authority are required");
        Uri tree = DocumentsContract.buildTreeDocumentUri(authority, "root");
        int modes = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        File root = FixtureDocumentsProvider.fixtureRoot(this);
        if (getIntent().getBooleanExtra("cleanup", false)) {
            revokeUriPermission(target, tree, modes);
            FixtureDocumentsProvider.deleteTree(root);
        } else {
            FixtureDocumentsProvider.deleteTree(root);
            if (!root.mkdirs()) throw new IllegalStateException("Cannot create fixture directory");
            try (FileOutputStream output = new FileOutputStream(new File(root, "input.bin"))) {
                output.write(FixtureDocumentsProvider.BYTES);
            } catch (IOException error) {
                throw new IllegalStateException(error);
            }
            grantUriPermission(target, tree, modes | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        }
        finish();
    }
}
