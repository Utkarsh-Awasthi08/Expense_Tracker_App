import { Platform } from 'react-native';

/**
 * Central API Gateway Base URL.
 * All client traffic routes through gatewayService (port 8000).
 * - Android Emulator uses 10.0.2.2 to reach host machine.
 * - iOS Simulator / Web uses localhost.
 */
export const API_BASE_URL = Platform.OS === 'android'
  ? 'http://10.0.2.2:8000'
  : 'http://localhost:8000';
