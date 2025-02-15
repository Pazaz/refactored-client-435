package org.runejs.client.net;

import org.runejs.client.GameSocket;
import org.runejs.client.cache.CacheArchive;
import org.runejs.client.io.Buffer;
import org.runejs.client.node.HashTable;
import org.runejs.client.node.NodeQueue;

import java.io.IOException;
import java.util.zip.CRC32;

public class UpdateServer implements IUpdateServer {
    public int ioExceptionsCount = 0;
    public int crcMismatchesCount = 0;

    private GameSocket updateServerSocket;
    private Buffer fileDataBuffer = new Buffer(8);
    private Buffer inboundFile;
    private Buffer crcTableBuffer;
    private HashTable highPriorityWriteQueue = new HashTable(4096);
    private HashTable highPriorityOutgoingRequests = new HashTable(32);
    private HashTable standardPriorityOutgoingRequests = new HashTable(4096);
    private HashTable standardPriorityWriteQueue = new HashTable(4096);
    private UpdateServerNode currentResponse;
    private CRC32 crc32 = new CRC32();
    private byte encryption = (byte) 0;
    private int highPriorityResponseCount = 0;
    private int standardPriorityWriteCount = 0;
    private int highPriorityWriteCount = 0;
    private int standardPriorityResponseCount = 0;
    private boolean highPriorityRequest;
    private NodeQueue pendingWriteQueue = new NodeQueue();
    private int blockOffset = 0;
    private int msSinceLastUpdate = 0;
    private long lastUpdateInMillis;
    private CacheArchive[] cacheArchiveLoaders = new CacheArchive[256];

    private enum Opcode {
        REQUEST(0),
        PRIORITY_REQUEST(1),
        LOGGED_IN(2),
        LOGGED_OUT(3),
        NEW_ENCRYPTION(4);

        private final int value;

        Opcode(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }
    }


    @Override
    public void receiveConnection(GameSocket socket, boolean isLoggedIn) {
        if(updateServerSocket != null) {
            try {
                updateServerSocket.kill();
            } catch(Exception exception) {
                exception.printStackTrace();
                /* empty */
            }
            updateServerSocket = null;
        }

        updateServerSocket = socket;
        resetRequests(isLoggedIn);
        fileDataBuffer.currentPosition = 0;
        inboundFile = null;
        blockOffset = 0;
        currentResponse = null;

        for(; ; ) {
            UpdateServerNode updateServerNode = (UpdateServerNode) highPriorityOutgoingRequests.getNextNode();
            if(updateServerNode == null) {
                break;
            }

            highPriorityWriteQueue.put(updateServerNode.key, updateServerNode);
            highPriorityResponseCount--;
            highPriorityWriteCount++;
        }

        for(; ; ) {
            UpdateServerNode updateServerNode = (UpdateServerNode) standardPriorityOutgoingRequests.getNextNode();
            if(updateServerNode == null) {
                break;
            }

            pendingWriteQueue.unshift(updateServerNode);
            standardPriorityWriteQueue.put(updateServerNode.key, updateServerNode);
            standardPriorityResponseCount--;
            standardPriorityWriteCount++;
        }

        if(encryption != 0) {
            try {
                Buffer fileRequestBuffer = new Buffer(4);
                fileRequestBuffer.putByte(Opcode.NEW_ENCRYPTION.getValue());
                fileRequestBuffer.putByte(encryption);
                fileRequestBuffer.putShortBE(0);
                updateServerSocket.sendDataFromBuffer(4, 0, fileRequestBuffer.buffer);
            } catch(java.io.IOException ioexception) {
                ioexception.printStackTrace();
                try {
                    updateServerSocket.kill();
                } catch(Exception exception) {
                    exception.printStackTrace();
                    /* empty */
                }
                updateServerSocket = null;
                ioExceptionsCount++;
            }
        }
        msSinceLastUpdate = 0;
        lastUpdateInMillis = System.currentTimeMillis();
    }


    @Override
    public boolean poll() {
        long l = System.currentTimeMillis();
        int currentMsSinceLastUpdate = (int) (l - lastUpdateInMillis);
        lastUpdateInMillis = l;
        if(currentMsSinceLastUpdate > 200) {
            currentMsSinceLastUpdate = 200;
        }
        msSinceLastUpdate += currentMsSinceLastUpdate;
        if(standardPriorityResponseCount == 0 && highPriorityResponseCount == 0 && standardPriorityWriteCount == 0 && highPriorityWriteCount == 0) {
            return true;
        }
        if(updateServerSocket == null) {
            return false;
        }

        try {
            if(msSinceLastUpdate > 30000) {
                throw new IOException();
            }

            // Immediate file requests
            for(/**/; highPriorityResponseCount < 20; highPriorityResponseCount++) {
                if(highPriorityWriteCount <= 0) {
                    break;
                }
                UpdateServerNode updateServerNode = (UpdateServerNode) highPriorityWriteQueue.getNextNode();
                Buffer buffer = new Buffer(4);
                buffer.putByte(Opcode.PRIORITY_REQUEST.getValue()); // immediate file request
                buffer.putMediumBE((int) updateServerNode.key); // file index + file id
                updateServerSocket.sendDataFromBuffer(4, 0, buffer.buffer);
                highPriorityOutgoingRequests.put(updateServerNode.key, updateServerNode);
                highPriorityWriteCount--;
            }

            // Queuable file requests
            for(/**/; standardPriorityResponseCount < 20 && standardPriorityWriteCount > 0; standardPriorityWriteCount--) {
                UpdateServerNode updateServerNode = (UpdateServerNode) pendingWriteQueue.next();
                Buffer buffer = new Buffer(4);
                buffer.putByte(Opcode.REQUEST.getValue()); // queued file request
                buffer.putMediumBE((int) updateServerNode.key); // file index + file id
                updateServerSocket.sendDataFromBuffer(4, 0, buffer.buffer);
                updateServerNode.clear();
                standardPriorityOutgoingRequests.put(updateServerNode.key, updateServerNode);
                standardPriorityResponseCount++;
            }

            for(int i1 = 0; i1 < 100; i1++) {
                int dataAvailable = updateServerSocket.inputStreamAvailable();
                if(dataAvailable < 0) {
                    throw new IOException();
                }
                if(dataAvailable == 0) {
                    break;
                }

                msSinceLastUpdate = 0;

                int read = 0;
                if(currentResponse == null) {
                    read = 8;
                } else if(blockOffset == 0) {
                    read = 1;
                }

                if(read <= 0) {
                    int inboundFileLength = inboundFile.buffer.length + -currentResponse.padding;
                    int i_37_ = -blockOffset + 512;
                    if(-inboundFile.currentPosition + inboundFileLength < i_37_) {
                        i_37_ = inboundFileLength - inboundFile.currentPosition;
                    }
                    if(i_37_ > dataAvailable) {
                        i_37_ = dataAvailable;
                    }
                    updateServerSocket.readDataToBuffer(inboundFile.currentPosition, i_37_, inboundFile.buffer);
                    if(encryption != 0) {
                        for(int i_38_ = 0; i_37_ > i_38_; i_38_++) {
                            inboundFile.buffer[inboundFile.currentPosition + i_38_] = (byte) UpdateServer.xor(inboundFile.buffer[inboundFile.currentPosition + i_38_], encryption);
                        }
                    }

                    inboundFile.currentPosition += i_37_;
                    blockOffset += i_37_;

                    if(inboundFileLength == inboundFile.currentPosition) {
                        if(currentResponse.key == 16711935) { // crc table file key
                            crcTableBuffer = inboundFile;
                            for(int i = 0; i < 256; i++) {
                                CacheArchive archive = cacheArchiveLoaders[i];
                                if(archive != null) {
                                    crcTableBuffer.currentPosition = 4 * i + 5;
                                    int indexCrcValue = crcTableBuffer.getIntBE();
                                    archive.requestLatestVersion(indexCrcValue);
                                }
                            }
                        } else {
                            crc32.reset();
                            crc32.update(inboundFile.buffer, 0, inboundFileLength);
                            int fileRealCrcValue = (int) crc32.getValue();
                            if(~currentResponse.crc != ~fileRealCrcValue) {
                                try {
                                    updateServerSocket.kill();
                                } catch(Exception exception) {
                                }
                                encryption = (byte) (int) (Math.random() * 255.0 + 1.0);
                                updateServerSocket = null;
                                crcMismatchesCount++;
                                return false;
                            }

                            ioExceptionsCount = 0;
                            crcMismatchesCount = 0;
                            currentResponse.cacheArchive.method196((currentResponse.key & 0xff0000L) == 16711680L, (int) (currentResponse.key & 0xffffL), highPriorityRequest, inboundFile.buffer);
                        }

                        currentResponse.unlink();
                        currentResponse = null;
                        inboundFile = null;
                        blockOffset = 0;

                        if(!highPriorityRequest) {
                            standardPriorityResponseCount--;
                        } else {
                            highPriorityResponseCount--;
                        }
                    } else {
                        if(blockOffset != 512) {
                            break;
                        }
                        blockOffset = 0;
                    }
                } else {
                    int pos = -fileDataBuffer.currentPosition + read;
                    if(pos > dataAvailable) {
                        pos = dataAvailable;
                    }

                    updateServerSocket.readDataToBuffer(fileDataBuffer.currentPosition, pos, fileDataBuffer.buffer);

                    if(encryption != 0) {
                        for(int i = 0; pos > i; i++) {
                            fileDataBuffer.buffer[fileDataBuffer.currentPosition + i] =
                                    (byte) xor(fileDataBuffer.buffer[fileDataBuffer.currentPosition + i], encryption);
                        }
                    }

                    fileDataBuffer.currentPosition += pos;
                    if(read > fileDataBuffer.currentPosition) {
                        break;
                    }

                    if(currentResponse == null) {
                        fileDataBuffer.currentPosition = 0;
                        int fileIndexId = fileDataBuffer.getUnsignedByte();
                        int fileId = fileDataBuffer.getUnsignedShortBE();
                        int fileCompression = fileDataBuffer.getUnsignedByte();
                        int fileSize = fileDataBuffer.getIntBE();
                        long fileKey = ((long) fileIndexId << 16) + fileId;
                        UpdateServerNode updateServerNode = (UpdateServerNode) highPriorityOutgoingRequests.getNode(fileKey);
                        highPriorityRequest = true;

                        if(updateServerNode == null) {
                            updateServerNode = (UpdateServerNode) standardPriorityOutgoingRequests.getNode(fileKey);
                            highPriorityRequest = false;
                        }

                        if(updateServerNode == null) {
                            throw new IOException();
                        }

                        currentResponse = updateServerNode;
                        int compressionSizeOffset = fileCompression == 0 ? 5 : 9;
                        inboundFile = new Buffer(currentResponse.padding + compressionSizeOffset + fileSize);
                        inboundFile.putByte(fileCompression);
                        inboundFile.putIntBE(fileSize);
                        blockOffset = 8;
                        fileDataBuffer.currentPosition = 0;
                    } else if(blockOffset == 0) {
                        if(fileDataBuffer.buffer[0] == -1) {
                            fileDataBuffer.currentPosition = 0;
                            blockOffset = 1;
                        } else {
                            currentResponse = null;
                        }
                    }
                }
            }

            return true;
        } catch(IOException ioexception) {
            ioexception.printStackTrace();

            try {
                updateServerSocket.kill();
            } catch(Exception exception) {
                exception.printStackTrace();
            }

            ioExceptionsCount++;
            updateServerSocket = null;

            return false;
        }
    }

    public void enqueueFileRequest(boolean isPriority, CacheArchive archive, int archiveIndexId, int fileId, byte arg4, int expectedCrc) {
        long fileKey = fileId + ((long) archiveIndexId << 16);
        UpdateServerNode updateServerNode = (UpdateServerNode) highPriorityWriteQueue.getNode(fileKey);

        if (updateServerNode == null) {
            updateServerNode = (UpdateServerNode) highPriorityOutgoingRequests.getNode(fileKey);
            if (updateServerNode == null) {
                updateServerNode = (UpdateServerNode) standardPriorityWriteQueue.getNode(fileKey);
                if (updateServerNode == null) {
                    if (!isPriority) {
                        updateServerNode = (UpdateServerNode) standardPriorityOutgoingRequests.getNode(fileKey);
                        if (updateServerNode != null)
                            return;
                    }
                    updateServerNode = new UpdateServerNode();
                    updateServerNode.crc = expectedCrc;
                    updateServerNode.padding = arg4;
                    updateServerNode.cacheArchive = archive;
                    if (isPriority) {
                        highPriorityWriteQueue.put(fileKey, updateServerNode);
                        highPriorityWriteCount++;
                    } else {
                        pendingWriteQueue.push(updateServerNode);
                        standardPriorityWriteQueue.put(fileKey, updateServerNode);
                        standardPriorityWriteCount++;
                    }
                } else if (isPriority) {
                    updateServerNode.clear();
                    highPriorityWriteQueue.put(fileKey, updateServerNode);
                    standardPriorityWriteCount--;
                    highPriorityWriteCount++;
                }
            }
        }
    }

    /**
     * TODO suspicious name
     */
    public void moveRequestToPendingQueue(int arg0, int arg2) {
        long l = (arg0 << 16) + arg2;
        UpdateServerNode updateServerNode = (UpdateServerNode) standardPriorityWriteQueue.getNode(l);
        if (updateServerNode != null) {
            pendingWriteQueue.unshift(updateServerNode);
        }
    }

    public void requestArchiveChecksum(CacheArchive cacheArchive, int cacheIndexId) {
        if (crcTableBuffer == null) {
            enqueueFileRequest(true, null, 255, 255, (byte) 0, 0);
            cacheArchiveLoaders[cacheIndexId] = cacheArchive;
        } else {
            crcTableBuffer.currentPosition = 5 + cacheIndexId * 4;
            int i = crcTableBuffer.getIntBE();
            cacheArchive.requestLatestVersion(i);
        }
    }

    private static int xor(int arg0, int arg1) {
        return arg0 ^ arg1;
    }


    @Override
    public void resetRequests(boolean loggedIn) {
        if (updateServerSocket != null) {
            try {
                Buffer buffer = new Buffer(4);
                buffer.putByte(loggedIn ? Opcode.LOGGED_IN.getValue() : Opcode.LOGGED_OUT.getValue());
                buffer.putMediumBE(0);
                updateServerSocket.sendDataFromBuffer(4, 0, buffer.buffer);
            } catch (java.io.IOException ioexception) {
                ioexception.printStackTrace();
                try {
                    updateServerSocket.kill();
                } catch (Exception exception) {
                    exception.printStackTrace();
                    /* empty */
                }
                updateServerSocket = null;
                ioExceptionsCount++;
            }
        }
    
    }

    @Override
    public void close() {
        if(updateServerSocket != null) {
            updateServerSocket.kill();
        }
    }

    @Override
    public int getLoadedPercentage(int volume, int file) {
        long l = (long) ((volume << 16) + file);
        if (currentResponse == null || currentResponse.key != l)
            return 0;
        return 1 + inboundFile.currentPosition * 99 / (inboundFile.buffer.length + -currentResponse.padding);
    }

    @Override
    public int getActiveTaskCount(boolean includeStandardPriority, boolean includeHighPriority) {
        int total = 0;
        if (includeHighPriority) {
            total += highPriorityResponseCount + highPriorityWriteCount;
        }
        if (includeStandardPriority) {
            total += standardPriorityResponseCount + standardPriorityWriteCount;
        }
        return total;
    }
}
