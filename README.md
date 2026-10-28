# AirScroll
Control your phone with your hand, no touching. Show fingers and hold still for a moment:
1 finger = up | 2 = down | 3 = left | 4 = right | 5 (thumb out) = double tap. Hold to repeat (not double tap).
Home screen: point with 1 finger (a circle follows your fingertip), open your middle finger (2 fingers) to open the circled app.
If Accessibility was already ON from an older version, switch AirScroll OFF and ON again once (it now also checks which app is in front).
The "Pointer mode" button in the app: auto (home screen only) / always on / off.
1. New PRIVATE GitHub repo `airscroll-apk`, push this folder (git init; git add .; git commit -m init; git branch -M main; git remote add origin <url>; git push -u origin main).
2. Actions builds it (~5 min, it downloads the 7 MB hand model itself). APK: Releases > latest > app-debug.apk.
3. Install, open: Allow camera > Accessibility > AirScroll > ON > Start.
   If Android says "Restricted setting": phone Settings > Apps > AirScroll > top-right dots > Allow restricted settings.
4. Open Instagram reels, show 1 finger and hold still for a moment.
Tests (logic only): javac -d /tmp/gt app/src/main/java/com/jeevitesh/airscroll/GestureLogic.java tests/GestureTest.java && java -cp /tmp/gt com.jeevitesh.airscroll.GestureTest
