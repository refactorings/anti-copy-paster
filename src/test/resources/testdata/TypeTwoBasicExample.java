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
        System.out.println(result);
        return result;
    }

    public void duplicate2a(int n) {
        float s=0.0; //C1
        float p =1.0;
        for (int j=1; j<=n; j++)
            {s=s + j;
            p = p * j;
            foo(s, p); }
    }

    public void duplicate2b(int n) {
        float s=0.0; //C1
        float p =1.0;
        for (int j=1; j<=n; j++)
            {s=s + j;
            p = p * j;
            foo(p, s); }
    }

    public void duplicate2c(int n) {
        int sum=0; //C1
        int prod =1;
        for (int i=1; i<=n; i++)
            {sum=sum + i;
            prod = prod * i;
            foo(sum, prod); }
    }

    public void duplicate2d(int n) {
        int sum=0; //C1
        int prod =1;
        for (int i=1; i<=n; i++)
            {sum=sum + (i*i);
            prod = prod * (i*i);
            foo(sum, prod); }
    }
}