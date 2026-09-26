import React from 'react';
import { Text, StyleSheet, Platform } from 'react-native';

interface CustomTextProps {
  style?: any;
  children?: React.ReactNode;
  [key: string]: any;
}

const CustomText = ({style, children, ...props}: CustomTextProps) => {
    return (
      <Text style={[styles.text, style]} {...props}>
        {children}
      </Text>
    );
  };
  
  const styles = StyleSheet.create({
    text: {
      color: 'black',
      fontFamily: 'Helvetica',
    },
  });
  
  export default CustomText;