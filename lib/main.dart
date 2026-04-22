import 'package:flutter/material.dart';
import 'screens/rfid_screen.dart';
import 'screens/barcode_screen.dart';
import 'services/zebra_service.dart';

void main() {
  runApp(const RfidSampleApp());
}

class RfidSampleApp extends StatelessWidget {
  const RfidSampleApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'RFID Sample',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(colorScheme: ColorScheme.fromSeed(seedColor: Colors.blue), useMaterial3: true),
      home: const HomeScreen(),
    );
  }
}

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  int _selectedIndex = 0;

  // Two tabs: RFID Inventory + Barcode (mirrors original app's two Activities)
  static const _pages = [RfidScreen(), BarcodeScreen()];

  @override
  void initState() {
    super.initState();
    // Initialize native handlers once the Flutter engine is ready.
    // Native side mirrors MainApplication.onCreate() initialization flow.
    ZebraService.instance.initialize();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: IndexedStack(
        // IndexedStack keeps both screens alive so the RFID handler stays connected
        // when switching tabs (same intent as keepConnexion flag in original sample)
        index: _selectedIndex,
        children: _pages,
      ),
      bottomNavigationBar: NavigationBar(
        selectedIndex: _selectedIndex,
        onDestinationSelected: (i) => setState(() => _selectedIndex = i),
        destinations: const [
          NavigationDestination(icon: Icon(Icons.nfc), label: 'RFID'),
          NavigationDestination(icon: Icon(Icons.qr_code_scanner), label: 'Barcode'),
        ],
      ),
    );
  }
}
