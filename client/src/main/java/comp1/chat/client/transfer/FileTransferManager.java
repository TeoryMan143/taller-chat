package comp1.chat.client.transfer;

import ChatApp.ChatRoomPrx;
import ChatApp.FileMetadata;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class FileTransferManager {

    public static final int CHUNK_SIZE = 32 * 1024; // 32 KB por chunk
    private static final String DOWNLOADS_DIR = "downloads";

    private static class IncomingFile {
        final FileMetadata metadata;
        final String from;
        final String target; // usuario o sala
        final byte[][] chunks;
        int receivedChunksCount = 0;

        IncomingFile(FileMetadata metadata, String from, String target) {
            this.metadata = metadata;
            this.from = from;
            this.target = target;
            this.chunks = new byte[metadata.totalChunks][];
        }
    }

    private final Map<String, IncomingFile> incomingTransfers = new ConcurrentHashMap<>();

    public FileTransferManager() {
        try {
            Files.createDirectories(Paths.get(DOWNLOADS_DIR));
        } catch (IOException e) {
            System.err.println("[FILE] No se pudo crear la carpeta downloads: " + e.getMessage());
        }
    }

    public void sendFile(ChatRoomPrx service, String from, String target, boolean isRoom, File file) throws Exception {
        if (!file.exists() || !file.isFile()) {
            throw new IllegalArgumentException("El archivo no existe: " + file.getAbsolutePath());
        }

        long totalSize = file.length();
        int totalChunks = (int) Math.ceil((double) totalSize / CHUNK_SIZE);
        if (totalChunks == 0) {
            totalChunks = 1;
        }

        String fileId = UUID.randomUUID().toString();
        FileMetadata metadata = new FileMetadata(fileId, file.getName(), totalSize, totalChunks);

        System.out.printf("[FILE] Enviando '%s' (%d bytes, %d chunks) a %s...%n",
                file.getName(), totalSize, totalChunks, target);

        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[CHUNK_SIZE];
            int chunkIndex = 0;
            int bytesRead;

            while ((bytesRead = fis.read(buffer)) != -1) {
                byte[] chunkData = (bytesRead == buffer.length)
                        ? buffer.clone()
                        : Arrays.copyOf(buffer, bytesRead);

                if (isRoom) {
                    service.sendRoomFileChunk(from, target, metadata, chunkIndex, chunkData);
                } else {
                    service.sendFileChunk(from, target, metadata, chunkIndex, chunkData);
                }

                chunkIndex++;
                if (chunkIndex % 10 == 0 || chunkIndex == totalChunks) {
                    System.out.printf("[FILE] Progreso: %d/%d chunks enviados%n", chunkIndex, totalChunks);
                }
            }
        }
        System.out.printf("[FILE] Archivo '%s' transmitido con exito.%n", file.getName());
    }

    public synchronized void onFileChunkReceived(String from, String target, FileMetadata metadata, int chunkIndex, byte[] data) {
        IncomingFile incoming = incomingTransfers.computeIfAbsent(metadata.fileId,
                id -> new IncomingFile(metadata, from, target));

        if (incoming.chunks[chunkIndex] == null) {
            incoming.chunks[chunkIndex] = data;
            incoming.receivedChunksCount++;

            if (incoming.receivedChunksCount == metadata.totalChunks) {
                // Reconstruir archivo en disco
                saveCompleteFile(incoming);
                incomingTransfers.remove(metadata.fileId);
            }
        }
    }

    private void saveCompleteFile(IncomingFile incoming) {
        Path destDir = Paths.get(DOWNLOADS_DIR);
        Path destFile = destDir.resolve(incoming.metadata.fileName);

        // Evitar sobreescritura accidental agregando timestamp si ya existe
        if (Files.exists(destFile)) {
            String name = incoming.metadata.fileName;
            String base = name;
            String ext = "";
            int dotIdx = name.lastIndexOf('.');
            if (dotIdx > 0) {
                base = name.substring(0, dotIdx);
                ext = name.substring(dotIdx);
            }
            destFile = destDir.resolve(base + "_" + System.currentTimeMillis() + ext);
        }

        try (FileOutputStream fos = new FileOutputStream(destFile.toFile())) {
            for (byte[] chunk : incoming.chunks) {
                if (chunk != null) {
                    fos.write(chunk);
                }
            }
            System.out.printf("%n[ARCHIVO RECIBIDO] '%s' (%d bytes) de '%s' guardado en: %s%n> ",
                    incoming.metadata.fileName, incoming.metadata.totalSize, incoming.from, destFile.toAbsolutePath());
        } catch (IOException e) {
            System.err.printf("[FILE] Error al guardar archivo '%s': %s%n", incoming.metadata.fileName, e.getMessage());
        }
    }
}
