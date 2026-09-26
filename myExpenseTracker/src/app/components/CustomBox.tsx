import { StyleSheet, View } from 'react-native';
import React from 'react';
import { Box } from '@gluestack-ui/themed';
import CustomText from './CustomText';

interface CustomBoxProps {
  style?: any;
  children?: React.ReactNode;
  [key: string]: any;
}

const CustomBox = ({style = {}, children, ...props}: CustomBoxProps) => {
    return (
      <View>
        <Box style={[styles.headingContainer, style.mainBox, style.styles]}>
          <View>{children}</View>
        </Box>
        <Box style={[styles.shadowContainer, style.shadowBox, style.styles]} />
      </View>
    );
  };
  
  export default CustomBox;
  
  const styles = StyleSheet.create({
    headingContainer: {
      padding: 20,
      borderColor: 'black',
      borderWidth: 1,
      position: 'relative',
      backgroundColor: 'black',
    },

    shadowContainer: {
      position: 'absolute',
      top: 5,
      left: 5,
      right: -5,
      bottom: -5,
      backgroundColor: 'gray',
      zIndex: -1,
    },
    mainBox: {
      borderColor: 'black',
      backgroundColor: 'black',
    },
    shadowBox: {
      backgroundColor: 'gray',
    },
  });