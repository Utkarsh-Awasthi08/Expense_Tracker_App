import React, { useEffect, useState } from 'react';
import { View, StyleSheet, TouchableOpacity, Text } from 'react-native';
import { useNavigation } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { API_BASE_URL } from '../api/config';
import { User } from 'lucide-react-native';

type RootStackParamList = {
  Profile: undefined;
};
type NavigationProp = NativeStackNavigationProp<RootStackParamList, 'Profile'>;

function Nav(): React.JSX.Element {
  const navigation = useNavigation<NavigationProp>();
  const [initials, setInitials] = useState('');
  const [greeting, setGreeting] = useState('');

  useEffect(() => {
    const hour = new Date().getHours();
    if (hour < 12) setGreeting('Good morning');
    else if (hour < 17) setGreeting('Good afternoon');
    else setGreeting('Good evening');

    (async () => {
      const token = await AsyncStorage.getItem('accessToken');
      if (!token) return;
      const res = await fetch(`${API_BASE_URL}/user/v1/me`, {
        headers: { Authorization: 'Bearer ' + token },
      });
      if (res.ok) {
        const data = await res.json();
        if (data.first_name) {
          const first = data.first_name.charAt(0).toUpperCase();
          const last = data.last_name ? data.last_name.charAt(0).toUpperCase() : '';
          setInitials(first + last);
        }
      }
    })();
  }, []);

  return (
    <View style={styles.container}>
      <View>
        <Text style={styles.greeting}>{greeting} 👋</Text>
        <Text style={styles.brand}>SpendWise</Text>
      </View>
      <TouchableOpacity style={styles.avatar} onPress={() => navigation.navigate('Profile')}>
        {initials ? (
          <Text style={styles.avatarText}>{initials}</Text>
        ) : (
          <User size={20} color="#818cf8" />
        )}
      </TouchableOpacity>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingHorizontal: 20,
    paddingTop: 16,
    paddingBottom: 12,
    backgroundColor: '#0f172a',
  },
  greeting: {
    fontSize: 13,
    color: '#64748b',
    fontWeight: '500',
    marginBottom: 2,
  },
  brand: {
    fontSize: 20,
    fontWeight: '800',
    color: '#f1f5f9',
    letterSpacing: 0.3,
  },
  avatar: {
    width: 44,
    height: 44,
    borderRadius: 22,
    backgroundColor: '#1e1b4b',
    borderWidth: 2,
    borderColor: '#4f46e5',
    alignItems: 'center',
    justifyContent: 'center',
  },
  avatarText: {
    color: '#818cf8',
    fontWeight: '700',
    fontSize: 15,
  },
});

export default Nav;