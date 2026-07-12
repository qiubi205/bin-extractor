package com.binextractor;

import android.Manifest;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_FILE_PICK = 1001;
    private static final int REQUEST_OUTPUT_PICK = 1002;
    private static final int REQUEST_PERMISSION = 1003;
    private static final int REQUEST_MANAGE_STORAGE = 1004;

    private TextView fileInfoText;
    private TextView resultText;
    private Button extractButton;
    private Button selectFileButton;
    private Button selectOutputButton;

    private Uri selectedFileUri;
    private String selectedFileName;
    private long selectedFileSize;
    private String outputDir;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        fileInfoText = findViewById(R.id.fileInfoText);
        resultText = findViewById(R.id.resultText);
        extractButton = findViewById(R.id.extractButton);
        selectFileButton = findViewById(R.id.selectFileButton);
        selectOutputButton = findViewById(R.id.selectOutputButton);

        selectFileButton.setOnClickListener(v -> pickFile());
        selectOutputButton.setOnClickListener(v -> pickOutputDir());
        extractButton.setOnClickListener(v -> startExtraction());

        checkStoragePermission();
    }

    private void checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                new AlertDialog.Builder(this)
                        .setTitle("Storage Permission")
                        .setMessage("This app needs storage access to read .bin files and save extracted content.")
                        .setPositiveButton("Grant", (d, w) -> {
                            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALLOW_FOREGROUND_SERVICE);
                            intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                            intent.setData(Uri.parse("package:" + getPackageName()));
                            startActivityForResult(intent, REQUEST_MANAGE_STORAGE);
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        REQUEST_PERMISSION);
            }
        }
    }

    private void pickFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_FILE_PICK);
    }

    private void pickOutputDir() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            startActivityForResult(intent, REQUEST_OUTPUT_PICK);
        } else {
            // Fallback to app's external files dir
            outputDir = new File(getExternalFilesDir(null), "extracted").getAbsolutePath();
            new File(outputDir).mkdirs();
            selectOutputButton.setText("Output: " + outputDir);
            updateExtractButton();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;

        if (requestCode == REQUEST_FILE_PICK) {
            selectedFileUri = data.getData();
            updateFileInfo();
        } else if (requestCode == REQUEST_OUTPUT_PICK) {
            Uri treeUri = data.getData();
            if (treeUri != null) {
                // Persist permission
                final int takeFlags = data.getFlags()
                        & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                getContentResolver().takePersistableUriPermission(treeUri, takeFlags);

                outputDir = treeUri.toString();
                selectOutputButton.setText("Output: " + getDisplayPath(treeUri));
                updateExtractButton();
            }
        } else if (requestCode == REQUEST_MANAGE_STORAGE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    Toast.makeText(this, "Storage permission granted", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Storage permission still required", Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    private String getDisplayPath(Uri uri) {
        String path = uri.getPath();
        if (path != null) {
            String[] parts = path.split(":");
            return parts.length > 1 ? parts[1] : "/";
        }
        return "Selected";
    }

    private void updateFileInfo() {
        if (selectedFileUri == null) return;

        // Get file name and size from content resolver
        String displayName = "Unknown";
        long size = 0;

        try (android.database.Cursor cursor = getContentResolver().query(
                selectedFileUri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                int sizeIdx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE);
                if (nameIdx >= 0) displayName = cursor.getString(nameIdx);
                if (sizeIdx >= 0) size = cursor.getLong(sizeIdx);
            }
        } catch (Exception e) {
            displayName = selectedFileUri.getLastPathSegment();
        }

        selectedFileName = displayName;
        selectedFileSize = size;

        String info = "📄 " + displayName + "\n" + formatFileSize(size);
        fileInfoText.setText(info);
        updateExtractButton();
    }

    private void updateExtractButton() {
        extractButton.setEnabled(selectedFileUri != null && outputDir != null);
    }

    private void startExtraction() {
        if (selectedFileUri == null || outputDir == null) return;

        // Copy the file to a temp location for processing
        ProgressDialog progress = new ProgressDialog(this);
        progress.setMessage(getString(R.string.extracting));
        progress.setIndeterminate(true);
        progress.setCancelable(false);
        progress.show();

        new Thread(() -> {
            try {
                // Copy selected file to temp
                File tempFile = new File(getCacheDir(), "input.bin");
                try (java.io.InputStream is = getContentResolver().openInputStream(selectedFileUri);
                     FileOutputStream fos = new FileOutputStream(tempFile)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = is.read(buf)) > 0) {
                        fos.write(buf, 0, len);
                    }
                }

                // Extract
                List<BinExtractor.ExtractedFile> files = BinExtractor.extract(tempFile);
                tempFile.delete();

                // Save to output
                int saved = 0;
                StringBuilder log = new StringBuilder();

                for (BinExtractor.ExtractedFile f : files) {
                    // Determine actual path
                    File outFile;
                    if (outputDir.startsWith("content://")) {
                        // Using SAF - save to app cache, then copy via ContentResolver
                        File cacheOut = new File(getCacheDir(), "extracted");
                        cacheOut.mkdirs();
                        outFile = new File(cacheOut, f.name);
                        outFile.getParentFile().mkdirs();
                    } else {
                        outFile = new File(outputDir, f.name);
                        outFile.getParentFile().mkdirs();
                    }

                    try (FileOutputStream fos = new FileOutputStream(outFile)) {
                        fos.write(f.data);
                    }
                    saved++;
                    log.append("✅ ").append(f.name)
                            .append(" (").append(formatFileSize(f.size)).append(")\n");
                }

                if (outputDir.startsWith("content://")) {
                    // Copy from cache to SAF URI
                    File cacheDir = new File(getCacheDir(), "extracted");
                    copyToSaF(cacheDir, Uri.parse(outputDir));
                    // Cleanup
                    deleteRecursive(cacheDir);
                }

                final int savedCount = saved;
                final String logStr = log.toString();

                runOnUiThread(() -> {
                    progress.dismiss();
                    resultText.setText(logStr);
                    Toast.makeText(MainActivity.this,
                            "Saved " + savedCount + " files", Toast.LENGTH_LONG).show();
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    progress.dismiss();
                    resultText.setText("❌ Error: " + e.getMessage());
                    Toast.makeText(MainActivity.this,
                            "Extraction failed", Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void copyToSaF(File srcDir, Uri destTreeUri) throws IOException {
        File[] files = srcDir.listFiles();
        if (files == null) return;

        android.documentfile.DocumentFile destDir = android.documentfile.DocumentFile.fromTreeUri(this, destTreeUri);
        if (destDir == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                android.documentfile.DocumentFile subDir = destDir.createDirectory(file.getName());
                if (subDir != null) copyToSaF(file, subDir.getUri());
            } else {
                android.documentfile.DocumentFile docFile = destDir.createFile("application/octet-stream", file.getName());
                if (docFile != null) {
                    try (java.io.InputStream is = new java.io.FileInputStream(file);
                         java.io.OutputStream os = getContentResolver().openOutputStream(docFile.getUri())) {
                        byte[] buf = new byte[8192];
                        int len;
                        while ((len = is.read(buf)) > 0) {
                            os.write(buf, 0, len);
                        }
                    }
                }
            }
        }
    }

    private void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursive(child);
            }
        }
        file.delete();
    }

    private String formatFileSize(long bytes) {
        if (bytes <= 0) return "0 B";
        final String[] units = {"B", "KB", "MB", "GB"};
        int unitIdx = (int) (Math.log10(bytes) / Math.log10(1024));
        if (unitIdx >= units.length) unitIdx = units.length - 1;
        double size = bytes / Math.pow(1024, unitIdx);
        DecimalFormat df = new DecimalFormat("#.##");
        return df.format(size) + " " + units[unitIdx];
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Permission granted", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Storage permission denied", Toast.LENGTH_LONG).show();
            }
        }
    }
}
