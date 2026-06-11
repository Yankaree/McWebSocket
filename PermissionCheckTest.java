import net.minecraft.commands.CommandSourceStack;
public class PermissionCheckTest {
    public static void main(String[] args) {
        for (var method : CommandSourceStack.class.getMethods()) {
            if(method.getName().contains("has")) {
                System.out.println(method.getName());
            }
        }
    }
}
