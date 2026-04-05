package common;
import common.IdentityMapper;

public class IdentityTest {
    public static void main(String[] args) {
        String s1 = "Gemini";
        String s2 = new String("Gemini"); // Forces a new memory address

        if (s1 == s2) {
            System.out.println("JVM says: They are the same address.");
        } else {
            System.out.println("JVM says: They are DIFFERENT addresses.");
        }

        // Now check if your Mapper agrees with the JVM
        IdentityMapper.BirthId id1 = IdentityMapper.getBirthId(s1, "test", 101);
        IdentityMapper.BirthId id2 = IdentityMapper.getBirthId(s2, "test", 102);

        System.out.println("Mapper says: Same ID? " + (id1.equals(id2)));
    }

    private static void printIdentity(String label, Object obj, int site) {
        IdentityMapper.BirthId id = IdentityMapper.getBirthId(obj, "test", site);
        System.out.printf("%-25s | Site: %d | Count: %d | Value: %s%n", 
            label, id.siteId, id.count, obj);
    }
}

