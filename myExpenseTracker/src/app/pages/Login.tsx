import React, { useState, useEffect } from 'react';
import {
  View,
  Text,
  TextInput,
  TouchableOpacity,
  StyleSheet,
  ActivityIndicator,
  KeyboardAvoidingView,
  Platform,
  Animated,
} from 'react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { API_BASE_URL } from '../api/config';

const SERVER_BASE_URL = API_BASE_URL;

const Login = ({ navigation }: { navigation: any }) => {
  const [step, setStep] = useState<'phone' | 'otp'>('phone');
  const [phone, setPhone] = useState('');
  const [otp, setOtp] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [countdown, setCountdown] = useState(0);
  const fadeAnim = React.useRef(new Animated.Value(0)).current;

  useEffect(() => {
    Animated.timing(fadeAnim, { toValue: 1, duration: 600, useNativeDriver: true }).start();
    checkExistingSession();
  }, []);

  useEffect(() => {
    if (countdown <= 0) return;
    const timer = setTimeout(() => setCountdown(c => c - 1), 1000);
    return () => clearTimeout(timer);
  }, [countdown]);

  const checkExistingSession = async () => {
    try {
      const accessToken = await AsyncStorage.getItem('accessToken');
      if (!accessToken) return;
      const res = await fetch(`${SERVER_BASE_URL}/auth/v1/ping`, {
        headers: { Authorization: 'Bearer ' + accessToken },
      });
      if (res.ok) { navigation.replace('Home'); return; }
      const refreshToken = await AsyncStorage.getItem('refreshToken');
      if (!refreshToken) return;
      const refreshRes = await fetch(`${SERVER_BASE_URL}/auth/v1/refreshToken`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ token: refreshToken }),
      });
      if (refreshRes.ok) {
        const data = await refreshRes.json();
        await AsyncStorage.setItem('accessToken', data.accessToken);
        await AsyncStorage.setItem('refreshToken', data.token);
        navigation.replace('Home');
      }
    } catch (_) {}
  };

  const requestOtp = async () => {
    const trimmedPhone = phone.trim();
    if (!trimmedPhone) { setError('Please enter your phone number.'); return; }
    setLoading(true); setError('');
    try {
      const res = await fetch(`${SERVER_BASE_URL}/auth/v1/otp/request`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ phone_number: trimmedPhone }),
      });
      if (res.status === 204) { setStep('otp'); setCountdown(60); }
      else if (res.status === 429) {
        const data = await res.json().catch(() => ({}));
        const retry = data.retry_after_seconds ?? 60;
        setCountdown(retry);
        setError(`Too many requests. Wait ${retry}s.`);
      } else {
        const data = await res.json().catch(() => ({}));
        setError(data.message || 'Failed to send OTP.');
      }
    } catch (_) { setError('Network error.'); }
    finally { setLoading(false); }
  };

  const verifyOtp = async () => {
    if (otp.trim().length !== 6) { setError('Enter the 6-digit code.'); return; }
    setLoading(true); setError('');
    try {
      const res = await fetch(`${SERVER_BASE_URL}/auth/v1/otp/verify`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ phone_number: phone.trim(), code: otp.trim() }),
      });
      if (res.ok) {
        const data = await res.json();
        await AsyncStorage.setItem('accessToken', data.accessToken);
        await AsyncStorage.setItem('refreshToken', data.token);
        navigation.replace('Home');
      } else { setError('Invalid or expired code.'); setOtp(''); }
    } catch (_) { setError('Network error.'); }
    finally { setLoading(false); }
  };

  return (
    <KeyboardAvoidingView style={styles.root} behavior={Platform.OS === 'ios' ? 'padding' : 'height'}>
      <Animated.View style={[styles.container, { opacity: fadeAnim }]}>
        <View style={styles.header}>
          <View style={styles.logoCircle}><Text style={styles.logoEmoji}>💰</Text></View>
          <Text style={styles.appName}>ExpenseTracker</Text>
          <Text style={styles.tagline}>Smart spending, smarter saving</Text>
        </View>
        <View style={styles.card}>
          {step === 'phone' ? (
            <>
              <Text style={styles.stepTitle}>Sign In</Text>
              <Text style={styles.stepSubtitle}>Enter your mobile number to receive a one-time code.</Text>
              <Text style={styles.inputLabel}>Phone Number</Text>
              <TextInput
                style={styles.input}
                value={phone}
                onChangeText={t => { setPhone(t); setError(''); }}
                placeholder="+91 98765 43210"
                placeholderTextColor="#94a3b8"
                keyboardType="phone-pad"
                returnKeyType="send"
                onSubmitEditing={requestOtp}
              />
              {!!error && <Text style={styles.errorText}>{error}</Text>}
              <TouchableOpacity style={[styles.btn, loading && styles.btnDisabled]} onPress={requestOtp} disabled={loading} activeOpacity={0.85}>
                {loading ? <ActivityIndicator color="#fff" /> : <Text style={styles.btnText}>Send OTP →</Text>}
              </TouchableOpacity>
            </>
          ) : (
            <>
              <Text style={styles.stepTitle}>Enter OTP</Text>
              <Text style={styles.stepSubtitle}>Code sent to <Text style={styles.highlight}>{phone}</Text></Text>
              <Text style={styles.inputLabel}>6-Digit Code</Text>
              <TextInput
                style={[styles.input, styles.otpInput]}
                value={otp}
                onChangeText={t => { setOtp(t.replace(/\D/g, '')); setError(''); }}
                placeholder="• • • • • •"
                placeholderTextColor="#94a3b8"
                keyboardType="number-pad"
                maxLength={6}
                returnKeyType="done"
                onSubmitEditing={verifyOtp}
              />
              {!!error && <Text style={styles.errorText}>{error}</Text>}
              <TouchableOpacity style={[styles.btn, loading && styles.btnDisabled]} onPress={verifyOtp} disabled={loading} activeOpacity={0.85}>
                {loading ? <ActivityIndicator color="#fff" /> : <Text style={styles.btnText}>Verify & Sign In →</Text>}
              </TouchableOpacity>
              <TouchableOpacity style={[styles.resendBtn, countdown > 0 && styles.resendDisabled]} onPress={() => { if (countdown === 0) { setStep('phone'); setOtp(''); setError(''); } }} disabled={countdown > 0}>
                <Text style={[styles.resendText, countdown > 0 && { color: '#64748b' }]}>
                  {countdown > 0 ? `Resend in ${countdown}s` : 'Change number / Resend OTP'}
                </Text>
              </TouchableOpacity>
            </>
          )}
        </View>
        <Text style={styles.footer}>No account? Just enter your number — we'll set one up automatically.</Text>
      </Animated.View>
    </KeyboardAvoidingView>
  );
};

export default Login;

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: '#0f172a' },
  container: { flex: 1, justifyContent: 'center', paddingHorizontal: 24, paddingBottom: 32 },
  header: { alignItems: 'center', marginBottom: 36 },
  logoCircle: { width: 80, height: 80, borderRadius: 40, backgroundColor: '#1e293b', alignItems: 'center', justifyContent: 'center', marginBottom: 16, borderWidth: 2, borderColor: '#6366f1' },
  logoEmoji: { fontSize: 36 },
  appName: { fontSize: 28, fontWeight: '800', color: '#f8fafc', letterSpacing: 0.5 },
  tagline: { fontSize: 14, color: '#94a3b8', marginTop: 6 },
  card: { backgroundColor: '#1e293b', borderRadius: 20, padding: 28, borderWidth: 1, borderColor: '#334155' },
  stepTitle: { fontSize: 22, fontWeight: '700', color: '#f8fafc', marginBottom: 8 },
  stepSubtitle: { fontSize: 14, color: '#94a3b8', marginBottom: 24, lineHeight: 20 },
  highlight: { color: '#818cf8', fontWeight: '600' },
  inputLabel: { fontSize: 12, fontWeight: '600', color: '#94a3b8', marginBottom: 8, textTransform: 'uppercase', letterSpacing: 0.8 },
  input: { backgroundColor: '#0f172a', borderWidth: 1, borderColor: '#334155', borderRadius: 12, paddingHorizontal: 16, paddingVertical: 14, fontSize: 16, color: '#f8fafc', marginBottom: 20 },
  otpInput: { fontSize: 22, letterSpacing: 8, textAlign: 'center', fontWeight: '700' },
  errorText: { color: '#f87171', fontSize: 13, marginBottom: 16, textAlign: 'center' },
  btn: { backgroundColor: '#6366f1', borderRadius: 12, paddingVertical: 16, alignItems: 'center' },
  btnDisabled: { opacity: 0.6 },
  btnText: { color: '#fff', fontSize: 16, fontWeight: '700' },
  resendBtn: { marginTop: 16, alignItems: 'center', paddingVertical: 8 },
  resendDisabled: { opacity: 0.5 },
  resendText: { color: '#818cf8', fontSize: 14, fontWeight: '600' },
  footer: { textAlign: 'center', color: '#475569', fontSize: 13, marginTop: 28, lineHeight: 18 },
});