import java.lang.reflect.Method;
import net.minecraft.commands.CommandSourceStack;

public class MethodFinder {
    public static void main(String[] args) {
        for (Method m : CommandSourceStack.class.getMethods()) {
            System.out.println(m.getName());
        }
    }
}
