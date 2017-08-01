import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.junit.Test;

public class TestOper {

    @Test
    public void test() throws Exception{
        String res_1 = "not1";
        String res_2 = "not2";
        String res_3 = "not3";
        String res_4 = "not4";
        Log logger = LogFactory.getLog(TestOper.class);
        Operation test_operation = new Operation();
        res_1 = test_operation.query("a");
        res_2 = test_operation.query("b");
        logger.debug(String.format("res_1:%s , res_2:%s" , res_1 , res_2));
        test_operation.transfer("a" , "b" , "10");
        res_3 = test_operation.query("a");
        res_4 = test_operation.query("b");
        logger.debug(String.format("res_3:%s , res_4:%s" , res_3 , res_4));
    }
}
