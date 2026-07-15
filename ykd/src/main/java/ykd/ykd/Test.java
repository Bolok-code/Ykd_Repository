package ykd.ykd;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class Test {
@GetMapping("/Hello")
    public String   Hello(){
    return "HelloWorld777";
}
}


