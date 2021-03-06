;; # Namespace scope
;;
;; Signal processing. You can find here 3 elements
;;
;; * Image pixels to/from raw (signal) converter
;; * Signal processing + effects
;; * Signal generators
;;
;; See examples 16 and 18

(ns clojure2d.extra.signal
  (:require [clojure2d.math :as m]
            [clojure2d.pixels :as p]
            [clojure2d.core :refer :all]
            [clojure2d.color :as c]
            [clojure.java.io :refer :all]
            [clojure2d.math.vector :as v]
            [clojure2d.math.random :as r])
  (:import [clojure2d.pixels Pixels]
           [clojure2d.math.vector Vec2 Vec3]
           [clojure.lang Counted]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)
(m/use-primitive-operators)

;; ## Signal
;;
;; Signal is simply java double array wrapped into `Signal` type. Each sample is represented by double from `[-1.0,1.0]` range (inclusive).

(deftype Signal [^doubles signal]
  Object
  (toString [_] (str "size=" (alength signal)))
  Counted
  (count [_] (alength signal)))

;; ### Pixels/RAW/Signal converters
;;
;; First group of functions helps to convert `Pixels` into `RAW` and `Signal` and vice versa. The main idea behind is to make conversion as flexible as possible and to simulate Audacity/SoX import options and various RAW representations. This is typical set of options used in sonification glitch process.
;;
;; Process goes like this
;;
;; * Pixels -> RAW
;;   * `:channels` - list of channels or `:all`
;;   * `:layout` - `:planar` or `:interleaved`
;;   * `:little-endian` - endiannes `true` of `false`
;;   * pack channel values into `long`, representation as 8 (one value), 16 (two values) or 24 (three values) bits with sign or unsigned.
;;
;; * RAW -> Signal
;;   * RAW values are mapped into `double` from [-1.0, 1.0] range
;;   * `:coding` - apply encoding from list `(:none :alaw :ulaw :alaw-rev :ulaw-rev)`
;;
;; Opposite direction is similar. For each conversion you have to provide configuration. Default configuration for both directions is defined under `pixels-default-configuration` variable.
;;
;; Code for converters is awfull as hell, it's optimized to be as fast as possible.

;; A-law constants
(def ^:const ^double alaw-A 87.6)
(def ^:const ^double alaw-rA (/ 1.0 alaw-A))
(def ^:const ^double alaw-lA (inc (m/log alaw-A)))
(def ^:const ^double alaw-rlA (/ 1.0 alaw-lA))

(defn alaw
  "A-law algorirthm"
  ^double [^double x]
  (let [absx (m/abs x)
        f (* alaw-rlA (m/sgn x))]
    (* f (if (< absx alaw-rA)
           (* alaw-A absx)
           (+ alaw-lA (m/log absx))))))

(defn alaw-rev
  "Reversed A-law algorithm"
  ^double [^double y]
  (let [absy (m/abs y)
        f (* alaw-rA (m/sgn y))
        v (* absy alaw-lA)]
    (* f (if (< absy alaw-rlA)
           v
           (m/exp (dec v))))))

;; u-law constants
(def ^:const ^double ulaw-U 255.0)
(def ^:const ^double ulaw-rU (/ 1.0 255.0))
(def ^:const ^double ulaw-U1 (inc ulaw-U))
(def ^:const ^double ulaw-rlnU1 (/ 1.0 (m/log ulaw-U1)))

(defn ulaw
  "u-law algorithm"
  ^double [^double x]
  (* (m/sgn x)
     ulaw-rlnU1
     (m/log (inc (* ulaw-U (m/abs x))))))

(defn ulaw-rev
  "Reversed u-law algorithm"
  ^double [^double y]
  (* (m/sgn y)
     ulaw-rU
     (dec (m/pow ulaw-U1 (m/abs y)))))

(defn- apply-sign
  "Convert unsigned long to signed one when `bits` representation is used.

   * (apply-sign 127 8) => 127
   * (apply-sign 128 8) => -128
   * (apply-sign 128 9) => 128"
  ^long [^long in ^long bits]
  (let [sh (- 64 bits)]
    (-> in
        (bit-shift-left sh)
        (bit-shift-right sh))))

(defn- pack-with-endianess-and-sign
  "Pack 1,2 or 3 values to integer (with sign and endiannes options). Returns one long value.

   * signed, 24 bits, little endian: (pack-with-endianess-and-sign 100 100 244 true false) => 16016484
   * unsigned, 24 bits, little endian: (pack-with-endianess-and-sign 100 100 244 true true) => -760732"
  (^long [^long x little-endian signed]
   (if signed (apply-sign x 8) x))
  (^long [^long x ^long y little-endian signed]
   (let [[^long a ^long b] (if little-endian [y x] [x y])
         val (bit-or (bit-shift-left (bit-and a 0xff) 8)
                     (bit-and b 0xff))]
     (if signed (apply-sign val 16) val)))
  ([x y z little-endian signed]
   (let [[^long a ^long b ^long c] (if little-endian [z y x] [x y z])
         val (bit-or (bit-shift-left (bit-and a 0xff) 16)
                     (bit-shift-left (bit-and b 0xff) 8)
                     (bit-and c 0xff))]
     (if signed (apply-sign val 24) val))))

;; Calculate minimum and maximum values for each of 3 packed variants (8, 16 and 24 bits)
(def ^:const ^long s8-min (apply-sign 0x80 8))
(def ^:const ^long s8-max (apply-sign 0x7f 8))
(def ^:const ^long s16-min (apply-sign 0x8000 16))
(def ^:const ^long s16-max (apply-sign 0x7fff 16))
(def ^:const ^long s24-min (apply-sign 0x800000 24))
(def ^:const ^long s24-max (apply-sign 0x7fffff 24))

;; Pack integeres int double value (signal representation)
;;
;; `(double-to-ints 24 (ints-to-double 22 33 44 true true) true true)`
;; `=> #object[clojure2d.math.vector.Vec3 0x3d2a4fd9 "[22.0, 33.0, 44.0]"]`

(defn ints-to-double
  "Convert integer values to double from [-1.0 1.0] range."
  (^double [x endianess sign]
   (let [v (pack-with-endianess-and-sign x endianess sign)]
     (if sign
       (m/norm v s8-min s8-max -1.0 1.0)
       (m/norm v 0 0xff -1.0 1.0))))
  (^double [x y endianess sign]
   (let [v (pack-with-endianess-and-sign x y endianess sign)]
     (if sign
       (m/norm v s16-min s16-max -1.0 1.0)
       (m/norm v 0 0xffff -1.0 1.0))))
  ([x y z endianess sign]
   (let [^long v (pack-with-endianess-and-sign x y z endianess sign)]
     (if sign
       (m/norm v s24-min s24-max -1.0 1.0)
       (m/norm v 0 0xffffff -1.0 1.0)))))

(defn double-to-ints
  "Convert double value to integers for given number of bits, endianness and sign. Return vector of integers."
  [^long bits ^double v little-endian sign]
  (let [vv (m/constrain v -1.0 1.0)
        restored (long (condp m/eq bits
                         8 (if sign
                             (m/norm vv -1.0 1.0 s8-min s8-max)
                             (m/norm vv -1.0 1.0 0 0xff))
                         16 (if sign
                              (m/norm vv -1.0 1.0 s16-min s16-max)
                              (m/norm vv -1.0 1.0 0 0xffff))
                         24 (if sign
                              (m/norm vv -1.0 1.0 s24-min s24-max)
                              (m/norm vv -1.0 1.0 0 0xffffff))))]
    (condp m/eq bits
      8 (bit-and restored 0xff)
      16 (let [a (bit-and restored 0xff)
               b (bit-and (bit-shift-right restored 8) 0xff)]
           (if little-endian (Vec2. a b) (Vec2. b a)))
      24 (let [a (bit-and restored 0xff)
               b (bit-and (bit-shift-right restored 8) 0xff)
               c (bit-and (bit-shift-right restored 16) 0xff)]
           (if little-endian (Vec3. a b c) (Vec3. c b a))))))

(defn- pre-layout-planar
  "Machinery which copy channel values into ints buffer to/from Pixels using to `:planar` layout."
  ([dir ^Pixels p channels ^ints buff]
   (loop [ch channels
          iter (int 0)]
     (when ch
       (let [channel (first ch)
             curr-iter (loop [idx (int 0)
                              titer (int iter)]
                         (if (< idx (.size p))
                           (do
                             (if dir
                               (aset ^ints buff titer ^int (p/get-value p channel idx))
                               (p/set-value p channel idx (aget ^ints buff titer)))
                             (recur (inc idx) (inc titer)))
                           titer))]
         (recur (next ch) (int curr-iter)))))
   buff)
  ([dir ^Pixels p channels]
   (let [buff (int-array (* (count channels) (.size p)))]
     (pre-layout-planar dir p channels buff))))

(defn- pre-layout-interleaved
  "Machinery which copy channel values into ints buffer to/from Pixels using to `:interleaved` layout."
  ([dir ^Pixels p channels ^ints buff]
   (loop [idx (int 0)
          iter (int 0)]
     (when (< idx (.size p))
       (let [curr-iter (loop [ch channels
                              titer (int iter)]
                         (if ch
                           (let [channel (first ch)]
                             (if dir
                               (aset ^ints buff titer ^int (p/get-value p channel idx))
                               (p/set-value p channel idx (aget ^ints buff titer)))
                             (recur (next ch) (inc titer)))
                           titer))]
         (recur (inc idx) (int curr-iter)))))
   buff)
  ([dir ^Pixels p channels]
   (let [buff (int-array (* (count channels) (.size p)))]
     (pre-layout-interleaved dir p channels buff))))

;; Default configuration for conversion between `Pixels` and `Signal`
(def pixels-default-configuration
  {:layout :planar
   :coding :none
   :signed false
   :bits 8
   :little-endian true
   :channels [0 1 2]})

;; List of map keys and possible values for conversion configuration between `Pixels` and `Signal`.
;;
;; * `:layout` [:planar :interleaved]
;; * `:coding` [:none :alaw :ulaw :alaw-rev :ulaw-rev]
;; * `:signed` [true false]
;; * `:bits` [8 16 24]
;; * `:little-endian` [true false]
;; * `:channels` :all or list of channels

(defn signal-from-pixels
  "Main entry point for `Pixels` to `Signal` conversions according to configuration."
  ([^Pixels p conf]
   (let [config (merge pixels-default-configuration conf) ;; prepare configuration 
         channels (if (= :all (:channels config)) [0 1 2 3] (:channels config)) ;; list and order of channels
         ^long b (:bits config) ;; number of bits used for representation
         e (:little-endian config) ;; endianness
         s (:signed config) ;; sign
         nb (bit-shift-right b 3) ;; number of packed values in signal value (1 - one value, 8 bits; 2 - two values; 16 bits; 3 - three values; 24 bits) 
         ^doubles buff (double-array (m/ceil (/ (* (count channels) (.size p)) (double nb)))) ;; target buffer
         ^ints layout (if (= :planar (:layout config)) ;; layout Pixels and give pure ints
                        (pre-layout-planar true p channels)
                        (pre-layout-interleaved true p channels))
         limit (- (alength layout) (dec nb)) ;; maximum number of values to process
         coding (condp = (:coding config) ;; find encoding function
                  :none identity
                  :alaw alaw
                  :ulaw ulaw
                  :alaw-rev alaw-rev
                  :ulaw-rev ulaw-rev)]

     (loop [idx (int 0) ;; take values, convert to double, encode and store into buffer
            bidx (int 0)]
       (when (< idx limit)
         (condp m/eq nb
           1 (aset ^doubles buff bidx ^double (coding (ints-to-double (aget ^ints layout idx) e s)))
           2 (aset ^doubles buff bidx ^double (coding (ints-to-double (aget ^ints layout idx) (aget ^ints layout (inc idx)) e s)))
           3 (aset ^doubles buff bidx ^double (coding (ints-to-double (aget ^ints layout idx) (aget ^ints layout (inc idx)) (aget ^ints layout (+ 2 idx)) e s))))
         (recur (+ idx nb) (inc bidx))))

     (Signal. buff)))
  ([p]
   (signal-from-pixels p {})))

(defn signal-to-pixels
  "Main entry point for `Signal` to `Pixels` conversions according to configuration."
  [^Pixels target ^Signal sig conf]
  (let [config (merge pixels-default-configuration conf) ;; prepare configuration
        channels (if (= :all (:channels config)) [0 1 2 3] (:channels config)) ;; list and order of channels
        ^long b (:bits config) ;; number of bits in signal
        e (:little-endian config) ;; endianness
        s (:signed config) ;; sign
        nb (>> b 3) ;; number of values)
        ^doubles buff (.signal sig) ;; extract signal
        ^ints layout (int-array (* (count channels) (.size target))) ;; prepare layout
        limit (- (alength layout) (dec nb)) ;; number of values to process
        coding (condp = (:coding config) ;; find encoding function
                 :none identity
                 :alaw alaw
                 :ulaw ulaw
                 :alaw-rev alaw-rev
                 :ulaw-rev ulaw-rev)]

    (loop [idx (int 0) ;; decode and extract ints from doubles 
           bidx (int 0)]
      (when (< idx limit)
        (let [v (coding (aget ^doubles buff bidx))]
          (condp = nb
            1 (aset ^ints layout idx ^int (double-to-ints 8 v e s)) ;; boxed math
            2 (let [^Vec2 v (double-to-ints 16 v e s)]
                (aset ^ints layout idx (int (.x v)))
                (aset ^ints layout (inc idx) (int (.y v))))
            3 (let [^Vec3 v (double-to-ints 24 v e s)]
                (aset ^ints layout idx (int (.x v)))
                (aset ^ints layout (inc idx) (int (.y v)))
                (aset ^ints layout (+ 2 idx) (int (.z v))))))
        (recur (+ idx nb) (inc bidx))))

    (if (= :planar (:layout config)) ;; store restored values into target (`Pixels`).
      (pre-layout-planar false target channels layout)
      (pre-layout-interleaved false target channels layout))

    target))

;; ## Signal processing
;;
;; Signal processing machinery and effects definitions.
;;
;; Every effect is a function packed into `EffectsList` type. Function accepts `sample` as double and current `state` value. And returns type `SampleAndState` with resulting sample and new state. When called without parameters returns inital state.
;; `EffectsList` type should be treated and one node of the list of effects. Effects can be composed this way.
;;
;; To create and effect use multimethod `make-effect` and pass effect name (keyword) and configuration map.
;;
;; `(make-effect :distort {:factor 0.43})`
;;
;; To process sample call `single-pass` with effect and sample. Result is stored in `sample` type field. `single-pass` returns effect in new state, so the context is kept.
;;
;; `(single-pass (make-effect :distort {:factor 0.43}) 0.5) => #object[clojure2d.extra.signal.EffectList 0x5e31cc40 "0.7688172043010754"]`
;;
;; To compose two or more effects call `compose-effects` functions. To reset state call `reset-effects`. To wrap effect function into `EffectsList` type call `make-effect-node`.
;;
;; `apply-effects` function processes whole `Signal` with provided effect. Optionally you can reset effects state every `reset` number of samples processed.

;; Type returned by effect function, consist of resulting sample and new effect state.
(deftype SampleAndState [^double sample state]
  Object
  (toString [_] (str sample))) ; sample and effect state, StateWithF or vector of StateWithF

;; Type representing list node consisting resulting sample, effect functions, state value and link to next node (or nil if last node)
(deftype EffectsList [^double sample effect state next]
  Object
  (toString [_] (str sample)))

(defn make-effect-node
  "Create `EffectsList` node from effect function and initial state"
  [f]
  (EffectsList. 0.0 f (f) nil))

(defn compose-effects
  "Compose list of effects, returns `EffectsList`."
  [^EffectsList e & es]
  (if (nil? es)
    e
    (EffectsList. (.sample e) (.effect e) (.state e) (apply compose-effects es))))

(defn reset-effects
  "Resets effects state to initial one."
  [^EffectsList e]
  (EffectsList. 0.0 (.effect e) ((.effect e)) (when-not (nil? (.next e))
                                                (reset-effects (.next e)))))

(defn single-pass
  "Process on sample using effects, returns `EffectsList` with result and new effect states."
  [^EffectsList e sample]
  (if (nil? (.next e))
    (let [^SampleAndState r ((.effect e) sample (.state e))]
      (EffectsList. (.sample r) (.effect e) (.state r) nil))
    (let [^EffectsList prev (single-pass (.next e) sample)]
      (let [^SampleAndState r ((.effect e) (.sample prev) (.state e))]
        (EffectsList. (.sample r) (.effect e) (.state r) prev)))))

(defn apply-effects
  "Apply effects to signal. If `reset` > 0, reinit state each `reset` number of samples. Returns new signal."
  ([effects ^Signal s ^long reset]
   (let [len (count s)
         in (.signal s)
         out (double-array len)]
     (loop [idx (int 0)
            effects-and-state effects]
       (when (< idx len)
         (let [sample (aget ^doubles in idx)
               ^EffectsList res (single-pass effects-and-state sample)
               idx+ (inc idx)] 
           (aset ^doubles out idx ^double (.sample res))
           (recur idx+
                  (if (and (pos? reset) (zero? ^long (mod idx+ reset)))
                    (reset-effects effects)
                    res)))))
     (Signal. out)))
  ([effects s] (apply-effects effects s 0)))

(defn- process-pixels
  "Process pixels as signal based on config for given pixels p. Store result in given target t."
  [p t effects config config-back reset]
  (signal-to-pixels t (apply-effects effects (signal-from-pixels p config) reset) config-back))

(defn make-effects-filter
  "Process `Pixels` as `Signal`. Provide configurations to properly convert Pixels to Signal and vice versa (default stored in `pixels-default-configuration` variable).
   Optionally set `reset` value (reinit effects' state after `reset` samples. Filter operates on one channel at time and is defined to be used with `filter-channels`. See `filter-pixels` to process lineary (and take benefit of layouts)"
  ([effects config config-back reset]
   (fn [ch target p]
     (let [c {:channels [ch]}]
       (process-pixels p target effects (merge config c) (merge config-back c) reset))))
  ([effects]
   (make-effects-filter effects {} {} 0))
  ([effects reset]
   (make-effects-filter effects {} {} reset))
  ([effects config config-back]
   (make-effects-filter effects config config-back 0)))

(defn apply-effects-to-pixels
  "Filter pixels as signal, directly. Pixels are not filtered channel by channel."
  ([effects config config-back reset pixels]
   (process-pixels pixels (p/clone-pixels pixels) effects config config-back reset))
  ([effects pixels] (apply-effects-to-pixels effects {} {} 0 pixels))
  ([effects reset pixels] (apply-effects-to-pixels effects {} {} reset pixels))
  ([effects config config-back pixels] (apply-effects-to-pixels effects config config-back 0 pixels)))

;; ## Helper functions

(defn db-to-linear
  "DB to Linear (audacity)"
  ^double [^double x]
  (m/pow 10.0 (/ x 20.0)))

(defn linear-to-db
  "Linear to DB (audacity)"
  ^double [x]
  (* 20.0 (m/log10 x)))

;; ## Effects / Filters

(defmulti make-effect (fn [m conf] m))

;; ### Simple Low/High pass filters
;;
;; Names: `:simple-lowpass`, `:simple-highpass`
;;
;; Confguration:
;;
;; * `:rate` - sample rate (default 44100.0)
;; * `:cutoff` - cutoff frequency (default 2000.0)

(defn- calc-filter-alpha
  "Calculate alpha factor"
  ^double [^double rate ^double cutoff]
  (let [tinterval (/ rate)
        tau (/ (* cutoff m/TWO_PI))]
    (/ tinterval (+ tau tinterval))))

(defmethod make-effect :simple-lowpass [_ {:keys [rate cutoff]
                                           :or {rate 44100.0 cutoff 2000.0}}]
  (let [alpha (calc-filter-alpha rate cutoff)] 
    (make-effect-node (fn
                        ([^double sample ^double prev]
                         (let [s1 (* sample alpha)
                               s2 (- prev (* prev alpha))
                               nprev (+ s1 s2)]
                           (SampleAndState. nprev nprev)))
                        ([] 0.0)))))

(defmethod make-effect :simple-highpass [_ conf]
  (let [lpfilter (make-effect :simple-lowpass conf)]
    (make-effect-node (fn
                        ([^double sample lp]
                         (let [^EffectsList res (single-pass lp sample)]
                           (SampleAndState. (- sample (.sample res)) res)))
                        ([] lpfilter)))))

;; ### Biquad filters

;; Store biquad effect configuration in `BiquadConf` type
(deftype BiquadConf [^double b0 ^double b1 ^double b2 ^double a1 ^double a2])

(defn biquad-eq-params
  "Calculate configuration for biquad equalizer
   fc - center frequency
   gain
   bw - bandwidth
   fs - sample rate"
  [^double fc ^double gain ^double bw ^double fs]
  (let [w (/ (* m/TWO_PI (m/constrain fc 1.0 (* 0.5 fs))) fs)
        cw (m/cos w)
        sw (m/sin w)
        J (m/pow 10.0 (* gain 0.025))
        g (-> bw
              (m/constrain 0.0001 4.0)
              (* m/LN2_2 w)
              (/ sw)
              (m/sinh)
              (* sw))
        a0r (/ (+ 1.0 (/ g J)))

        b0 (* a0r (+ 1.0 (* g J)))
        b1 (* a0r -2.0 cw)
        b2 (* a0r (- 1.0 (* g J)))
        a1 (- b1)
        a2 (* a0r (- (/ g J) 1.0))]
    (BiquadConf. b0 b1 b2 a1 a2)))

(defn biquad-hs-params 
  "Calculate configuration for biquad high shelf
   fc - center frequency
   gain
   slope - shelf slope
   fs - sample rate"
  [^double fc ^double gain ^double slope ^double fs]
  (let [w (/ (* m/TWO_PI (m/constrain fc 1.0 (* 0.5 fs))) fs)
        cw (m/cos w)
        sw (m/sin w)
        A (m/pow 10.0 (* gain 0.025))
        iA (inc A)
        dA (dec A)
        b (m/sqrt (- (/ (inc (* A A)) (m/constrain slope 0.0001 1.0)) (* dA dA)))
        apc (* cw iA)
        amc (* cw dA)
        bs (* b sw)
        a0r (->> amc
                 (- iA)
                 (+ bs)
                 (/ 1.0))

        b0 (* a0r A (+ iA amc bs))
        b1 (* a0r A -2.0 (+ dA apc))
        b2 (* a0r A (- (+ iA amc) bs))
        a1 (* a0r -2.0 (- dA apc))
        a2 (* a0r (+ (dec (- A)) amc bs))]
    (BiquadConf. b0 b1 b2 a1 a2)))

(defn biquad-ls-params 
  "Calculate configuration for biquad low shelf
   fc - center frequency
   gain
   slope - shelf slope
   fs - sample rate"
  [^double fc ^double gain ^double slope ^double fs]
  (let [w (/ (* m/TWO_PI (m/constrain fc 1.0 (* 0.5 fs))) fs)
        cw (m/cos w)
        sw (m/sin w)
        A (m/pow 10.0 (* gain 0.025))
        iA (inc A)
        dA (dec A)
        b (m/sqrt (- (/ (inc (* A A)) (m/constrain slope 0.0001 1.0)) (* dA dA)))
        apc (* cw iA)
        amc (* cw dA)
        bs (* b sw)
        a0r (->> amc
                 (+ iA)
                 (+ bs)
                 (/ 1.0))

        b0 (* a0r A (- (+ iA bs) amc))
        b1 (* a0r A 2.0 (- dA apc))
        b2 (* a0r A (- iA amc bs))
        a1 (* a0r 2.0 (+ dA apc))
        a2 (* a0r (+ bs (- (dec (- A)) amc)))]
    (BiquadConf. b0 b1 b2 a1 a2)))

(defn biquad-lp-params
  "Calculate configuration for biquad low pass"
  [^double fc ^double bw ^double fs]
  (let [omega (* m/TWO_PI (/ fc fs))
        sn (m/sin omega)
        cs (m/cos omega)
        alpha (* sn (m/sinh (* m/LN2_2 bw (/ omega sn))))
        a0r (/ (inc alpha))
        cs- (- 1.0 cs)
        
        b0 (* a0r 0.5 cs-)
        b1 (* a0r cs-)
        b2 b0
        a1 (* a0r 2.0 cs)
        a2 (* a0r (dec alpha))]
    (BiquadConf. b0 b1 b2 a1 a2)))

(defn biquad-hp-params
  "Calculate configuration for biquad high pass"
  [^double fc ^double bw ^double fs]
  (let [omega (* m/TWO_PI (/ fc fs))
        sn (m/sin omega)
        cs (m/cos omega)
        alpha (* sn (m/sinh (* m/LN2_2 bw (/ omega sn))))
        a0r (/ (inc alpha))
        cs+ (inc cs)
        
        b0 (* a0r 0.5 cs+)
        b1 (* a0r (- cs+))
        b2 b0
        a1 (* a0r 2.0 cs)
        a2 (* a0r (dec alpha))]
    (BiquadConf. b0 b1 b2 a1 a2)))

(defn biquad-bp-params
  "Calculate configuration for biquad band pass"
  [^double fc ^double bw ^double fs]
  (let [omega (* m/TWO_PI (/ fc fs))
        sn (m/sin omega)
        cs (m/cos omega)
        alpha (* sn (m/sinh (* m/LN2_2 bw (/ omega sn))))
        a0r (/ (inc alpha))
        
        b0 (* a0r alpha)
        b1 0.0
        b2 (* a0r (- alpha))
        a1 (* a0r 2.0 cs)
        a2 (* a0r (dec alpha))]
    (BiquadConf. b0 b1 b2 a1 a2)))

;; Store state in `StateBiquad` type.
(deftype StateBiquad [^double x2 ^double x1 ^double y2 ^double y1])

(defn make-biquad-filter
  "Create biquad effect based on passed configuration"
  [^BiquadConf c]
  (make-effect-node (fn
                      ([^double sample ^StateBiquad state]
                       (let [y (-> (* (.b0 c) sample)
                                   (+ (* (.b1 c) (.x1 state)))
                                   (+ (* (.b2 c) (.x2 state)))
                                   (+ (* (.a1 c) (.y1 state)))
                                   (+ (* (.a2 c) (.y2 state))))]
                         (SampleAndState. y (StateBiquad. (.x1 state) sample (.y1 state) y))))
                      ([] (StateBiquad. 0.0 0.0 0.0 0.0)))))

;; ### Biquad equalizer
;;
;; Name: `:biquad-eq`
;;
;; Configuration:
;;
;; * `:fc` - center frequency
;; * `:gain` - gain
;; * `:bw` - bandwidth (default: 1.0)
;; * `:fs` - sampling rate (defatult: 44100.0)
(defmethod make-effect :biquad-eq [_ {:keys [fc gain bw fs]
                                      :or {fc 0.0 gain 0.0 bw 1.0 fs 44100.0}}]
  (make-biquad-filter (biquad-eq-params fc gain bw fs)))

;; ### Biquad high/low shelf
;;
;; Names: `:biquad-hs`, `:biquad-ls`
;;
;; Configuration:
;;
;; * `:fc` - center frequency
;; * `:gain` - gain
;; * `:slope` - shelf slope (default 1.5)
;; * `:fs` - sampling rate (default 44100.0)
(defmethod make-effect :biquad-hs [_ {:keys [fc gain slope fs]
                                      :or {fc 0.0 gain 0.0 slope 1.5 fs 44100.0}}]
  (make-biquad-filter (biquad-hs-params fc gain slope fs)))

(defmethod make-effect :biquad-ls [_ {:keys [fc gain slope fs]
                                      :or {fc 0.0 gain 0.0 slope 1.5 fs 44100.0}}]
  (make-biquad-filter (biquad-ls-params fc gain slope fs)))

;; ### Biquad lowpass/highpass/bandpass
;;
;; Names: `:biquad-lp`, `:biquad-hp`, `:biquad-bp`
;;
;; Configuration:
;;
;; * `:fc` - cutoff/center frequency
;; * `:bw` - bandwidth (default 1.0)
;; * `:fs` - sampling rate (default 44100.0)

(defn- lhb-params
  "Create parameters for lp hp and bp biquad filters."
  [f {:keys [fc bw fs]
      :or {fc 0.0 bw 1.0 fs 44100.0}}]
  (f fc bw fs))

(defmethod make-effect :biquad-lp [_ conf] (make-biquad-filter (lhb-params biquad-lp-params conf)))
(defmethod make-effect :biquad-hp [_ conf] (make-biquad-filter (lhb-params biquad-hp-params conf)))
(defmethod make-effect :biquad-bp [_ conf] (make-biquad-filter (lhb-params biquad-bp-params conf)))

;; ### DJ Equalizer
;;
;; Name: `:dj-eq`
;;
;; Configuration:
;;
;; * `:high` - high frequency gain (10000Hz)
;; * `:mid` - mid frequency gain (1000Hz)
;; * `:low` - low frequency gain (100Hz)
;; * `:shelf-slope` - shelf slope for high frequency (default 1.5)
;; * `:peak-bw` - peak bandwidth for mid and low frequencies (default 1.0)
;; * `:rate` - sampling rate (default 44100.0)
(defmethod make-effect :dj-eq [_ {:keys [hi mid low shelf-slope peak-bw rate]
                                  :or {hi 0.0 mid 0.0 low 0.0 shelf-slope 1.5 peak-bw 1.0 rate 44100.0}}]
  (let [b (compose-effects
           (make-effect :biquad-hs {:fc 10000.0 :gain hi :slope shelf-slope :fs rate})
           (make-effect :biquad-eq {:fc 1000.0 :gain mid :bw peak-bw :fs rate})
           (make-effect :biquad-eq {:fc 100.0 :gain low :bw peak-bw :fs rate}))]
    (make-effect-node (fn
                        ([sample state]
                         (let [^EffectsList res (single-pass state sample)]
                           (SampleAndState. (.sample res) res)))
                        ([] b)))))

;; ### Phaser
;;
;; Name: `:phaser-allpass`
;;
;; Configuration: `:delay` - delay factor (default: 0.5)
(defmethod make-effect :phaser-allpass [_ {:keys [^double delay]
                                           :or {delay 0.5}}]
  (let [a1 (/ (- 1.0 delay) (inc delay))]
    (make-effect-node (fn
                        ([^double sample ^double zm1]
                         (let [y (+ zm1 (* sample (- a1)))
                               new-zm1 (+ sample (* y a1))]
                           (SampleAndState. y new-zm1)))
                        ([] 0.0)))))

;; ### Divider
;;
;; Name: `:divider`
;;
;; Configuration: `:denom` (long, default 2.0)
(deftype StateDivider [^double out ^double amp ^double count ^double lamp ^double last ^int zeroxs])

(defmethod make-effect :divider [_ {:keys [^long denom]
                                    :or {denom 2.0}}]
  (make-effect-node
   (fn
     ([^double sample ^StateDivider state]
      (let [count (inc (.count state))
            ^StateDivider s1 (if (bool-or (bool-and (> sample 0.0) (<= (.last state) 0.0))
                                          (bool-and (neg? sample) (>= (.last state) 0.0)))
                               (if (== denom 1)
                                 (StateDivider. (if (pos? (.out state)) -1.0 1.0) 0.0 0.0 (/ (.amp state) count) (.last state) 0)
                                 (StateDivider. (.out state) (.amp state) count (.lamp state) (.last state) (inc (.zeroxs state))))
                               (StateDivider. (.out state) (.amp state) count (.lamp state) (.last state) (.zeroxs state)))
            amp (+ (.amp s1) (m/abs sample))
            ^StateDivider s2 (if (bool-and (> denom 1)
                                           (== ^long (rem (.zeroxs s1) denom) (dec denom)))
                               (StateDivider. (if (pos? (.out s1)) -1.0 1.0) 0.0 0 (/ amp (.count s1)) (.last s1) 0)
                               (StateDivider. (.out s1) amp (.count s1) (.lamp s1) (.last s1) (.zeroxs s1)))]
        (SampleAndState. (* (.out s2) (.lamp s2)) (StateDivider. (.out s2) (.amp s2) (.count s2) (.lamp s2) sample (.zeroxs s2)))))
     ([] (StateDivider. 1.0 0.0 0.0 0.0 0.0 0.0)))))

;; ### FM filter
;;
;; Filter modulates and demodulates signal
;;
;; Name `:fm`
;;
;; Configuration:
;;
;; * `:quant` - quantization value (0.0 - if no quantization, default 10)
;; * `:omega` - carrier factor (default 0.014)
;; * `:phase` - deviation factor (default 0.00822)
(deftype StateFm [^double pre ^double integral ^double t lp])

(defmethod make-effect :fm [_ {:keys [^double quant ^double omega ^double phase]
                               :or {quant 10.0 omega 0.014 phase 0.00822}}]
  (let [lp-chain (compose-effects (make-effect :simple-lowpass {:rate 100000 :cutoff 25000})
                                  (make-effect :simple-lowpass {:rate 100000 :cutoff 10000})
                                  (make-effect :simple-lowpass {:rate 100000 :cutoff 1000}))]
    (make-effect-node
     (fn
       ([^double sample ^StateFm state]
        (let [sig (* sample phase)
              new-integral (+ (.integral state) sig)
              m (m/cos (+ new-integral (* omega (.t state))))
              ^double m (if (pos? quant)
                          (m/norm (unchecked-int (m/norm m -1.0 1.0 0.0 quant)) 0.0 quant -1.0 1.0)
                          m)
              dem (m/abs (- m (.pre state)))
              ^EffectsList res (single-pass (.lp state) dem)
              demf (/ (* 2.0 (- (.sample res) omega)) phase)]
          (SampleAndState. (m/constrain demf -1.0 1.0) (StateFm. m new-integral (inc (.t state)) res))))
       ([] (StateFm. 0.0 0.0 0.0 lp-chain))))))


;; ### Bandwidth limit
;;
;; https://searchcode.com/file/18573523/cmt/src/lofi.cpp#
;;
;; Name: `:bandwidth-limit`
;;
;; Confguration:
;;
;; * `:rate` - sample rate (default 44100.0)
;; * `:freq` - cutoff frequency (default 1000.0)
(defmethod make-effect :bandwidth-limit [_ {:keys [^double freq ^double rate]
                                            :or {freq 1000.0 rate 44100.0}}]
  (let [dx (/ freq rate)]
    (make-effect-node (fn
                        ([^double sample ^double state]
                         (let [res (if (>= sample state)
                                     (min (+ state dx) sample)
                                     (max (- state dx) sample))]
                           (SampleAndState. res res)))
                        ([] 0.0)))))

;; ### Distortion
;;
;; Name: `:distort`
;;
;; Confguration:
;;
;; * `:factor` - distortion factor (default 1.0)
(defmethod make-effect :distort [_ {:keys [^double factor]
                                    :or {factor 1.0}}]
  (let [nfact (inc factor)]
    (make-effect-node (fn 
                        ([^double sample state]
                         (let [div (+ factor (m/abs sample))
                               res (* nfact (/ sample div))]
                           (SampleAndState. res state)))
                        ([])))))

;; ### Fast overdrive
;;
;; Name: `:foverdrive`
;;
;; Confguration:
;;
;; * `:drive` - drive (default 2.0)
(defmethod make-effect :foverdrive [_ {:keys [^double drive]
                                       :or {drive 2.0}}]
  (let [drivem1 (dec drive)]
    (make-effect-node (fn
                        ([^double sample state]
                         (let [fx (m/abs sample)
                               res (/ (* sample (+ fx drive)) (inc (+ (* sample sample) (* fx drivem1))))]
                           (SampleAndState. res state)))
                        ([])))))

;; ### Decimator
;;
;; Name: `:decimator`
;;
;; Confguration:
;;
;; * `:bits` - bit depth (default 2)
;; * `:fs` - decimator sample rate (default 4410.0)
;; * `:rate` - input sample rate (default 44100.0)
(deftype StateDecimator [^double count ^double last])

(defmethod make-effect :decimator [_ {:keys [^double bits ^double fs ^double rate]
                                      :or {bits 2.0 fs 4410.0 rate 44100.0}}]
  (let [step (m/pow 0.5 (- bits 0.9999))
        stepr (/ step)
        ratio (/ fs rate)]
    (make-effect-node (fn
                        ([^double sample ^StateDecimator state]
                         (let [ncount (+ (.count state) ratio)]
                           (if (>= ncount 1.0)
                             (let [delta (* step ^double (m/remainder (->> sample
                                                                           m/sgn
                                                                           (* step 0.5)
                                                                           (+ sample)
                                                                           (* stepr)) 1.0))
                                   last (- sample delta)]
                               (SampleAndState. last (StateDecimator. (dec ncount) last)))
                             (SampleAndState. (.last state) (StateDecimator. ncount (.last state))))))
                        ([] (StateDecimator. 0.0 0.0))))))

;; ### BassTreble
;;
;; Name: `:basstreble`
;;
;; Configuration:
;;
;; * `:bass` - bass gain (default 1.0)
;; * `:treble` - treble gain (default 1.0)
;; * `:gain` - gain (default 0.0)
;; * `:rate` - sample rate (default 44100.0)
;; * `:slope` - slope for both (default 0.4)
;; * `:bass-freq` - bass freq (default 250.0)
;; * `:treble-freq` - treble freq (default 4000.0)
(deftype StateBassTreble [^double xn1Bass ^double xn2Bass ^double yn1Bass ^double yn2Bass
                          ^double xn1Treble ^double xn2Treble ^double yn1Treble ^double yn2Treble])

(defmethod make-effect :basstreble [_ {:keys [^double bass ^double treble ^double gain ^double rate ^double slope ^double bass-freq ^double treble-freq]
                                       :or {bass 1.0 treble 1.0 gain 0.0 rate 44100.0 slope 0.4 bass-freq 250.0 treble-freq 4000.0}}]
  (let [data-gain (db-to-linear gain)
        wb (/ (* m/TWO_PI bass-freq) rate)
        wt (/ (* m/TWO_PI treble-freq) rate) 
        cwb (m/cos wb)
        cwt (m/cos wt)
        ab (m/exp (/ (* 2.302585092994046 bass) 40.0))
        ab+ (inc ab)
        ab- (dec ab)
        at (m/exp (/ (* 2.302585092994046 treble) 40.0))
        at+ (inc at)
        at- (dec at)
        bb (m/sqrt (- (/ (inc (m/sq ab)) slope) (m/sq (dec ab))))
        bt (m/sqrt (- (/ (inc (m/sq at)) slope) (m/sq (dec at))))
        bswb (* bb (m/sin wb))
        bswt (* bt (m/sin wt))

        b0b (* ab (+ (- ab+ (* ab- cwb)) bswb))
        b1b (* 2.0 ab (- ab- (* ab+ cwb)))
        b2b (* ab (- (- ab+ (* ab- cwb)) bswb))
        a0b (+ (+ ab+ (* ab- cwb)) bswb)
        a1b (* -2.0 (+ ab- (* ab+ cwb)))
        a2b (- (+ ab+ (* ab- cwb)) bswb)

        b0t (* at (+ (+ at+ (* at- cwt)) bswt))
        b1t (* -2.0 at (+ at- (* at+ cwt)))
        b2t (* at (- (+ at+ (* at- cwt)) bswt))
        a0t (+ (- at+ (* at- cwt)) bswt)
        a1t (* 2.0 (- at- (* at+ cwt)))
        a2t (- (- at+ (* at- cwt)) bswt)]
    (make-effect-node (fn
                        ([^double sample ^StateBassTreble state]
                         (let [outb (/ (-> (* b0b sample)
                                           (+ (* b1b (.xn1Bass state)))
                                           (+ (* b2b (.xn2Bass state)))
                                           (- (* a1b (.yn1Bass state)))
                                           (- (* a2b (.yn2Bass state)))) a0b)
                               outt (/ (-> (* b0t outb)
                                           (+ (* b1t (.xn1Treble state)))
                                           (+ (* b2t (.xn2Treble state)))
                                           (- (* a1t (.yn1Treble state)))
                                           (- (* a2t (.yn2Treble state)))) a0t)]
                           (SampleAndState. (* outt data-gain)
                                            (StateBassTreble. sample (.xn1Bass state) outb (.yn1Bass state)
                                                              outb (.xn1Treble state) outt (.yn1Treble state)))))
                        ([] (StateBassTreble. 0.0 0.0 0.0 0.0
                                              0.0 0.0 0.0 0.0))))))

;; ### Echo (audacity)
;;
;; Name: `:echo`
;;
;; Configuration:
;;
;; * `:delay` - delay time in seconds (default 0.5)
;; * `:decay` - decay (amount echo in signal, default 0.5)
;; * `:rate` - sample rate (default 44100.0)
;;
;; Warning! Echo filter uses mutable array as state, don't use the same filter in paraller processing
;; See example 16
(deftype StateEcho [^doubles buffer ^int position])

(defmethod make-effect :echo [_ {:keys [^double delay ^double decay ^double rate]
                                 :or {delay 0.5 decay 0.5 rate 44100.0}}]
  (let [buffer-len (int (min 10000000 (* delay rate)))]
    (make-effect-node (fn
                        ([^double sample ^StateEcho state]
                         (let [result (+ sample (* decay (aget ^doubles (.buffer state) (.position state))))]
                           (aset ^doubles (.buffer state) (.position state) result)
                           (SampleAndState. result (StateEcho. (.buffer state) (rem (inc (.position state)) buffer-len)))))
                        ([]
                         (StateEcho. (double-array buffer-len 0.0) 0))))))

;; ### Vcf303
;;
;; Name: `:vcf303`
;;
;; Configuration:
;;
;; * `:rate` - sample rate (default 44100.0)
;; * `:trigger` - boolean, trigger some action (default `false`), set true when you reset filter every line
;; * `:cutoff` - cutoff frequency (values 0-1, default 0.8)
;; * `:resonance` - resonance (values 0-1, default 0.8)
;; * `:env-mod` - envelope modulation (values 0-1, default 0.5)
;; * `:decay` - decay (values 0-1, default 1.0)
;;
;; Warning: filter requires normalization
(deftype StateVcf303 [^double d1 ^double d2 ^double c0 ^int env-pos ^Vec3 abc])

(defmethod make-effect :vcf303 [_ {:keys [^double rate trigger ^double cutoff ^double resonance ^double env-mod ^double decay]
                                   :or {rate 44100.0 trigger false cutoff 0.8 resonance 0.8 env-mod 0.5 decay 1.0}}]
  (let [scale (/ m/PI rate)
        e0 (* scale
              (m/exp (-> (- 5.613 (* 0.8 env-mod))
                         (+ (* 2.1553 cutoff))
                         (- (* 0.7696 (- 1.0 resonance))))))        
        d (m/pow (->> decay
                      (* 2.3)
                      (+ 0.2)
                      (* rate)
                      (/ 1.0)
                      (m/pow 0.1)) 64.0)
        r (m/exp (- (* 3.455 resonance) 1.20))
        recalc-abc (fn [^double vc0]
                     (let [whopping (+ e0 vc0)
                           k (m/exp (/ (- whopping) r))
                           a (* (+ k k) (m/cos (+ whopping whopping)))
                           b (* (- k) k)
                           c (* 0.2 (- (- 1.0 a) b))]
                       (Vec3. a b c)))
        init-c0       (if trigger
                        (- (* scale
                              (m/exp (-> (+ 6.109 (* 1.5876 env-mod))
                                         (+ (* 2.1553 cutoff))
                                         (- (* 1.2 (- 1.0 resonance)))))) e0)
                        0.0)]    
    (make-effect-node (fn
                        ([^double sample ^StateVcf303 state]
                         (let [^Vec3 abc (.abc state)
                               result (-> (* (.x abc) (.d1 state))
                                          (+ (* (.y abc) (.d2 state)))
                                          (+ (* (.z abc) sample)))
                               d2 (.d1 state)
                               d1 result
                               env-pos (inc (.env-pos state))]
                           (if (>= env-pos 64)
                             (let [c0 (* d (.c0 state))]
                               (SampleAndState. result
                                                (StateVcf303. d1 d2 c0 0 (recalc-abc c0))))
                             (SampleAndState. result
                                              (StateVcf303. d1 d2 (.c0 state) env-pos abc)))))
                        ([] (StateVcf303. 0.0 0.0 init-c0 0 (recalc-abc init-c0)))))))

;; ### Slew limiter (http://git.drobilla.net/cgit.cgi/omins.lv2.git/tree/src/slew_limiter.c)
;;
;; Name: `:slew-limit`
;;
;; Configuration:
;;
;; * `:rate` - sample rate
;; * `:maxrise` - maximum change for rising signal (in terms of 1/rate steps, default 500)
;; * `:maxfall` - maximum change for falling singal (default 500)

(defmethod make-effect :slew-limit [_ {:keys [^double rate ^double maxrise ^double maxfall]
                                       :or {rate 44100.0 maxrise 500.0 maxfall 500.0}}]
  (let [maxinc (/ maxrise rate)
        maxdec (- (/ maxfall rate))]
    (make-effect-node (fn
                        ([^double sample ^double prev]
                         (let [increment (- sample prev) 
                               nsample (+ prev (m/constrain increment maxdec maxinc))]
                           (SampleAndState. nsample nsample)))
                        ([] 0.0)))))

;; ### mdaThruZero
;;
;; Name: `:mda-thru-zero`
;;
;; Configuration:
;;
;; * `:rate` - sample rate
;; * `:speed` - effect rate
;; * `:depth`
;; * `:mix`
;; * `:depth-mod`
;; * `:feedback`
;;
;; Warning: like `:echo` internal state is kept in doubles array.
(deftype StateMdaThruZero [^doubles buffer ^double ph ^long bp ^double f])

(defmethod make-effect :mda-thru-zero [_ {:keys [^double rate ^double speed ^double depth ^double mix ^double depth-mod ^double feedback]
                                          :or {rate 44100.0 speed 0.3 depth 0.43 mix 0.47 feedback 0.3 depth-mod 1.0}}]
  (let [rat (/ (* (m/pow 10.0 (- 2.0 (* 3.0 speed))) 2.0) rate)
        dep (* 2000.0 (m/sq depth))
        dem (- dep (* dep depth-mod))
        dep (- dep dem)
        wet mix
        dry (- 1.0 wet)
        fb (- (* 1.9 feedback) 0.95)]
    (make-effect-node (fn
                        ([^double sample ^StateMdaThruZero state]
                         (let [ph (+ (.ph state) rat)
                               ph (if (> ph 1.0) (- ph 2.0) ph)
                               bp (bit-and (dec (.bp state)) 0x7ff)]
                           (aset ^doubles (.buffer state) bp (+ sample (* fb (.f state))))
                           (let [tmpf (+ dem (* dep (- 1.0 (m/sq ph))))
                                 tmp (unchecked-int tmpf)
                                 tmpf (- tmpf tmp)
                                 tmp (bit-and (+ tmp bp) 0x7ff)
                                 tmpi (bit-and (inc tmp) 0x7ff)
                                 f (aget ^doubles (.buffer state) tmp)
                                 f (+ (* tmpf (- (aget ^doubles (.buffer state) tmpi) f)) f)
                                 result (+ (* sample dry) (* f wet))]
                             (SampleAndState. result (StateMdaThruZero. (.buffer state) ph bp f)))))
                        ([] (StateMdaThruZero. (double-array 2048) 0.0 0 0.0))))))

;; ## File operations

;; Load and save signal from and to file.
;; Representation is: 16 bit signed, big endian file
;; You can use Audacity/SOX utilities to convert files to audio.
(defn save-signal
  "Save signal to file"
  [^Signal s filename]
  (make-parents filename)
  (let [^java.io.DataOutputStream out (java.io.DataOutputStream. (output-stream filename))]
    (try
      (dotimes [i (alength ^doubles (.signal s))]
        (.writeShort out (short (m/cnorm (aget ^doubles (.signal s) i) -1.0 1.0 Short/MIN_VALUE Short/MAX_VALUE))))
      (.flush out)
      (finally (. out clojure.core/close)))
    s))

(defn load-signal
  "Read signal from file"
  [filename]
  (let [^java.io.File f (file filename)
        len (/ (.length f) 2)
        ^java.io.DataInputStream in (java.io.DataInputStream. (input-stream filename))
        ^doubles buffer (double-array len)]
    (try
      (dotimes [i len]
        (aset ^doubles buffer (int i) (double (m/cnorm (.readShort in) Short/MIN_VALUE Short/MAX_VALUE -1.0 1.0))))
      (finally (. in clojure.core/close)))
    (Signal. buffer)))

;; ## Signal generators
;;
;; Here you have defined multimethods to create waves from various oscilators
;;
;; Parameters are:
;;
;; * oscilator name (see `oscillators` variable)
;; * frequency
;; * amplitude
;; * phase (0-1)
;;
;; Multimethod creates oscillator function accepting `double` (time) and resulting `double` from [-1.0 1.0] range.

(defmulti make-wave (fn [f _ _ _] f))

(defmethod make-wave :sin [_ ^double f ^double a ^double p]
  (fn ^double [^double x]
    (* a
       (m/sin (+ (* p m/TWO_PI) (* x m/TWO_PI f))))))

(def snoise (r/make-perlin-noise (r/irand) 2))

(defmethod make-wave :noise [_ ^double f ^double a ^double p]
  (let [shift-noise (r/drand -5.0 5.0)]
    (fn ^double [^double x]
      (* a 2.0
         (- ^double (snoise (* (+ p x) f) shift-noise) 0.5)))))

(defmethod make-wave :saw [_ ^double f ^double a ^double p] 
  (fn ^double [^double x]
    (let [rp (* 2.0 a)
          p2 (* f ^double (mod (+ (* a p) a x) 1.0))]
      (* rp (- p2 (m/floor p2) 0.5)))))

(defmethod make-wave :square [_ ^double f ^double a ^double p]
  (fn ^double [^double x]
    (if (< ^double (mod (+ p (* x f)) 1.0) 0.5)
      a
      (- a))))

(defmethod make-wave :triangle [_ ^double f ^double a ^double p]
  (let [saw (make-wave :saw f a p)]
    (fn ^double [^double x]
      (- (* 2.0 (m/abs (saw x))) a))))

(defmethod make-wave :cut-triangle [_ ^double f ^double a ^double p]
  (let [tri (make-wave :triangle f a p)]
    (fn ^double [^double x]
      (let [namp (* 0.5 a)]
        (* 2.0 (m/constrain ^double (tri x) (- namp) namp))))))

;; List of all oscillators
(def oscillators [:sin :noise :saw :square :triangle :cut-triangle])

(defn sum-waves
  "Calculate sum of waves (list) for given values"
  ^double [fs ^double x]
  (reduce #(+ ^double %1 ^double (%2 x)) 0.0 fs))

(defn make-sum-wave
  "Create oscillator as a sum of given base oscillators."
  [fs]
  (partial sum-waves fs))

(defn make-signal-from-wave
  "Create Signal from oscillator. Parameters are: f - oscillator, samplerate and number of seconds."
  [f ^double samplerate ^double seconds]
  (let [len (* samplerate seconds)
        ^doubles buffer (double-array len)
        limit (dec seconds)]
    (dotimes [i len]
      (aset ^doubles buffer i ^double (f (m/norm i 0 len 0 limit))))
    (Signal. buffer)))
