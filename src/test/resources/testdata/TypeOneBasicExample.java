
import com.intellij.testFramework.TestDataFile;

@TestDataFile
public class test {

    public void sumProd(int n) {
        float sum=0.0; //C1
        float prod =1.0;
        for (int i=1; i<=n; i++)
            {sum=sum + i;
            prod = prod * i;
            foo(sum, prod); }
    }

    public float foo(float s, float p) {
        float result = s + p;
        return result;
    }

    public void duplicate1a(int n) {
        float sum=0.0; //C1
        float prod =1.0;
        for (int i=1; i<=n; i++)
            {sum=sum + i;
            prod = prod * i;
            foo(sum, prod); }
    }

    public void duplicate1b(int n) {
        float sum=0.0; //C1'
        float prod =1.0; //C
        for (int i=1; i<=n; i++)
            {sum=sum + i;
            prod = prod * i;
            foo(sum, prod); }
    }

    public void duplicate1c(int n) {
        float sum=0.0; //C1
        float prod =1.0;
        for (int i=1; i<=n; i++) {
            sum=sum + i;
            prod = prod * i;
            foo(sum, prod); }
    }
}