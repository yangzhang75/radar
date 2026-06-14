package com.shipradar.sim.nmea

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 各传感器生成器:典型值 → 期望字段串 + 校验和正确(每类 ≥2 断言)。 */
class SensorSentencesTest {

    private val t = UtcTime(12, 35, 19.0, day = 23, month = 3, year = 1994)

    // --- GNSS: GGA / RMC / VTG / GLL / ZDA ---------------------------------------------------

    private val gnss = GnssSensor(
        time = t, latitude = 48.5, longitude = 11.25, sogKn = 10.0, cogTrueDeg = 90.0,
        altitudeM = 545.4, geoidSepM = 46.9, talker = "GP",
    )

    @Test fun gnss_gga() = assertNmea(gnss.gga(), "GPGGA,123519.00,4830.0000,N,01115.0000,E,1,08,1.0,545.4,M,46.9,M,,")

    @Test fun gnss_rmc_has_ed6_nav_status() =
        assertNmea(gnss.rmc(), "GPRMC,123519.00,A,4830.0000,N,01115.0000,E,10.0,90.0,230394,,,A,S")

    @Test fun gnss_vtg_gll_zda() {
        assertNmea(gnss.vtg(), "GPVTG,90.0,T,,M,10.0,N,18.5,K,A")
        assertNmea(gnss.gll(), "GPGLL,4830.0000,N,01115.0000,E,123519.00,A,A")
        assertNmea(gnss.zda(), "GPZDA,123519.00,23,03,1994,00,00")
    }

    @Test fun gnss_emits_five_sentences() {
        val s = gnss.toSentences()
        assertEquals(5, s.size)
        s.forEach { assertTrue(it.startsWith("$") && it.endsWith("\r\n")) }
    }

    // --- Heading: HDT / THS / HDG / ROT ------------------------------------------------------

    private val heading = HeadingSensor(
        trueHeadingDeg = 274.07, thsMode = 'A', magneticSensorDeg = 270.5,
        deviationDeg = -2.3, variationDeg = 6.1, rateOfTurnDegMin = 1.5,
    )

    @Test fun heading_hdt_ths() {
        assertNmea(heading.hdt(), "HEHDT,274.1,T")
        assertNmea(heading.ths(), "HETHS,274.1,A")
    }

    @Test fun heading_hdg_signs_and_rot() {
        assertNmea(heading.hdg(), "HCHDG,270.5,2.3,W,6.1,E")
        assertNmea(heading.rot(), "TIROT,1.5,A")
    }

    @Test fun heading_omits_hdg_rot_when_absent() {
        val s = HeadingSensor(trueHeadingDeg = 0.0).toSentences()
        assertEquals(2, s.size) // 只有 HDT + THS
    }

    // --- Wind: MWV (R/T) / MWD ---------------------------------------------------------------

    private val wind = WindSensor(
        relativeAngleDeg = 180.0, relativeSpeed = 3.0, speedUnit = 'N',
        theoreticalAngleDeg = 180.0, theoreticalSpeed = 10.0,
        trueDirectionDeg = 270.0, trueSpeedKn = 10.0,
    )

    @Test fun wind_mwv_relative_and_theoretical() {
        assertNmea(wind.mwvRelative(), "WIMWV,180.0,R,3.0,N,A")
        assertNmea(wind.mwvTheoretical(), "WIMWV,180.0,T,10.0,N,A")
    }

    @Test fun wind_mwd() = assertNmea(wind.mwd(), "WIMWD,270.0,T,,M,10.0,N,5.1,M")

    // --- Water: VHW / VBW / VDR --------------------------------------------------------------

    private val water = WaterSpeedSensor(
        waterSpeedKn = 12.0, headingTrueDeg = 90.0,
        longWaterKn = 12.0, transWaterKn = -0.5, longGroundKn = 12.5, transGroundKn = -0.3,
        currentSetDeg = 45.0, currentDriftKn = 1.2,
    )

    @Test fun water_vhw_vbw() {
        assertNmea(water.vhw(), "VWVHW,90.0,T,,M,12.0,N,22.2,K")
        assertNmea(water.vbw(), "VWVBW,12.0,-0.5,A,12.5,-0.3,A")
    }

    @Test fun water_vdr() = assertNmea(water.vdr(), "VWVDR,45.0,T,,M,1.2,N")

    // --- Depth: DPT / DBT --------------------------------------------------------------------

    private val depth = DepthSensor(depthBelowTransducerM = 10.0, transducerOffsetM = -0.5, maxRangeScaleM = 200.0)

    @Test fun depth_dpt() = assertNmea(depth.dpt(), "SDDPT,10.0,-0.5,200.0")

    @Test fun depth_dbt_three_units() = assertNmea(depth.dbt(), "SDDBT,32.8,f,10.0,M,5.5,F")

    // --- Steering: RSA / HSC / HTC -----------------------------------------------------------

    @Test fun rudder_rsa_single() = assertNmea(RudderSensor(starboardRudderDeg = -5.0).rsa(), "AGRSA,-5.0,A,,V")

    @Test fun rudder_rsa_dual() = assertNmea(RudderSensor(starboardRudderDeg = -5.0, portRudderDeg = -4.5).rsa(), "AGRSA,-5.0,A,-4.5,A")

    private val pilot = Autopilot(commandedHeadingDeg = 92.5, headingReference = 'T', isCommand = true)

    @Test fun autopilot_hsc() = assertNmea(pilot.hsc(), "AGHSC,92.5,T,,M,C")

    @Test fun autopilot_htc() = assertNmea(pilot.htc(), "AGHTC,V,,,H,N,,,,,92.5,,,T,C")

    // --- Engine: RPM / XDR -------------------------------------------------------------------

    private val engine = EngineSensor(
        rpm = 720.0, source = 'E', number = 1, propellerPitchPct = 85.0,
        transducers = listOf(
            Transducer('C', 83.2, 'C', "Engine#0"),
            Transducer('P', 200000.0, 'P', "EngineOil#1", decimals = 0),
        ),
    )

    @Test fun engine_rpm() = assertNmea(engine.rpm(), "ERRPM,E,1,720.0,85.0,A")

    @Test fun engine_xdr_multi_transducer() =
        assertNmea(engine.xdr(), "ERXDR,C,83.2,C,Engine#0,P,200000,P,EngineOil#1")

    @Test fun engine_omits_xdr_when_no_transducers() =
        assertEquals(1, EngineSensor(rpm = 0.0).toSentences().size)
}
