package ru.vanillamon;

import org.junit.jupiter.api.Test;
import ru.vanillamon.domain.SpawnPolicy;
import ru.vanillamon.config.Settings;
import ru.vanillamon.domain.Species;
import java.nio.file.Path;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class SpawnPolicyTest {
    @Test void rarityIsOneRollPerIntervalWithGlobalAndLocalCaps(){
        assertTrue(SpawnPolicy.admit(0,20,0,3,.249,.25));assertFalse(SpawnPolicy.admit(0,20,0,3,.25,.25));
        assertFalse(SpawnPolicy.admit(20,20,0,3,0,.25));assertFalse(SpawnPolicy.admit(2,20,3,3,0,.25));
        Random random=new Random(1);int accepted=0;for(int i=0;i<100000;i++)if(SpawnPolicy.admit(0,20,0,3,random.nextDouble(),.25))accepted++;
        assertTrue(accepted>24500&&accepted<25500);
    }
    @Test void wholeFishBodyMustBeInsideWaterAndNeverOnLandOrInOneShallowCell(){
        assertTrue(SpawnPolicy.waterVolume(.5,61,.5,.8f,1.4f,-64,320,(x,y,z)->y==61||y==62));
        assertFalse(SpawnPolicy.waterVolume(.5,61,.5,.8f,1.4f,-64,320,(x,y,z)->y==61));
        assertFalse(SpawnPolicy.waterVolume(.5,61,.5,.8f,1.4f,-64,320,(x,y,z)->false));
        assertFalse(SpawnPolicy.waterVolume(.5,61,.5,1.5f,1.4f,-64,320,(x,y,z)->x==0&&z==0));
        assertFalse(SpawnPolicy.waterVolume(.5,319,.5,.8f,1.4f,-64,320,(x,y,z)->true));
    }
    @Test void nightIntervalsCrossMidnightSafely(){assertTrue(SpawnPolicy.time(23000,12500,23999));assertFalse(SpawnPolicy.time(6000,12500,23999));assertTrue(SpawnPolicy.time(1000,22000,4000));assertFalse(SpawnPolicy.time(12000,22000,4000));}

}
