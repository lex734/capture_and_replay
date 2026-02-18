package replay;

import common.BinarySchema;
import common.IdentityMapper;
import instr.SyncTransformer;

import java.io.File;
import java.io.RandomAccessFile;
import java.lang.instrument.Instrumentation;
import java.nio.channels.FileChannel;
import java.nio.MappedByteBuffer;

public class ReplayAgent {

    public static void premain(String agentArgs, Instrumentation inst) {
        System.out.println("[ReplayAgent] Initializing Enforcer...");

        try {
            // 1. Reset the "Brain" to ensure discovery order matches Capture
            IdentityMapper.reset();

            // 2. Load the Trace File
            File traceFile = new File("trace.bin");
            if (!traceFile.exists()) {
                System.err.println("[ReplayAgent] ERROR: trace.bin not found!");
                return;
            }

            long fileSize = traceFile.length();
            long totalEvents = fileSize / BinarySchema.RECORD_SIZE;

            RandomAccessFile raf = new RandomAccessFile(traceFile, "r");
            MappedByteBuffer buffer = raf.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, fileSize);
            raf.close();

            // 3. Initialize the Coordinator with the data
            ReplayCoordinator.init(buffer, totalEvents);

            System.out.println("[ReplayAgent] Loaded " + totalEvents + " events. Instrumentation active.");

            // 4. Add the Transformer (The mode is handled by System Property tool.mode=REPLAY)
            inst.addTransformer(new SyncTransformer(), true);

        } catch (Exception e) {
            System.err.println("[ReplayAgent] Failed to initialize:");
            e.printStackTrace();
        }
    }
}
