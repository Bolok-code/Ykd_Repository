package ykd.ykd;

import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;

@RestController
public class Test {
@GetMapping("/Hello")
    public String   Hello(){
    return "HelloWorld1234567";
}

}



